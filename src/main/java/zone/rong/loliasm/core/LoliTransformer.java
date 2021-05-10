package zone.rong.loliasm.core;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import zone.rong.loliasm.config.LoliConfig;
import zone.rong.loliasm.LoliLogger;
import zone.rong.loliasm.patches.*;

import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

public class LoliTransformer implements IClassTransformer {

    public static final boolean squashBakedQuads = LoliLoadingPlugin.isClient && LoliConfig.getConfig().bakedQuadsSquasher && !LoliLoadingPlugin.isOptifineInstalled;

    final Map<String, Function<byte[], byte[]>> transformations;

    public LoliTransformer() {
        LoliLogger.instance.info("The lolis are now preparing to bytecode manipulate your game.");
        LoliConfig.Data data = LoliConfig.getConfig();
        transformations = new Object2ObjectOpenHashMap<>();
        if (squashBakedQuads) {
            addTransformation("net.minecraft.client.renderer.block.model.BakedQuad", BakedQuadPatch::rewriteBakedQuad);
            addTransformation("net.minecraft.client.renderer.block.model.BakedQuadRetextured", BakedQuadRetexturedPatch::patchBakedQuadRetextured);
            addTransformation("net.minecraftforge.client.model.pipeline.UnpackedBakedQuad", UnpackedBakedQuadPatch::rewriteUnpackedBakedQuad);
            addTransformation("net.minecraftforge.client.model.pipeline.UnpackedBakedQuad$Builder", UnpackedBakedQuadPatch::rewriteUnpackedBakedQuad$Builder);
            addTransformation("zone.rong.loliasm.bakedquad.BakedQuadFactory", BakedQuadFactoryPatch::patchCreateMethod);
        }
        if (data.canonizeObjects) {
            addTransformation("net.minecraft.util.ResourceLocation", this::canonizeResourceLocationStrings);
            if (LoliLoadingPlugin.isClient) {
                addTransformation("net.minecraft.client.renderer.block.model.ModelResourceLocation", this::canonizeResourceLocationStrings);
                addTransformation("net.minecraft.client.renderer.block.model.multipart.ICondition", this::canonicalBoolConditions);
                addTransformation("net.minecraft.client.renderer.block.model.multipart.ConditionOr", bytes -> canonicalPredicatedConditions(bytes, true));
                addTransformation("net.minecraft.client.renderer.block.model.multipart.ConditionAnd", bytes -> canonicalPredicatedConditions(bytes, false));
                addTransformation("net.minecraft.client.renderer.block.model.multipart.ConditionPropertyValue", this::canonicalPropertyValueConditions);
                // addTransformation("net.minecraft.client.renderer.block.model.MultipartBakedModel$Builder", this::cacheMultipartBakedModels); TODO
            }
        }
        if (data.optimizeDataStructures) {
            if (LoliLoadingPlugin.isClient) {
                addTransformation("net.minecraft.client.audio.SoundRegistry", this::removeDupeMapFromSoundRegistry);
                addTransformation("net.minecraft.client.audio.SoundEventAccessor", this::removeInstancedRandom);
            }
            addTransformation("net.minecraft.util.registry.RegistrySimple", this::removeValuesArrayFromRegistrySimple);
            addTransformation("net.minecraft.nbt.NBTTagCompound", this::nbtTagCompound$replaceDefaultHashMap);
            addTransformation("net.minecraftforge.fml.common.discovery.ModCandidate", this::removePackageField);
        }
        if (data.optimizeFurnaceRecipes) {
            addTransformation("net.minecraft.item.crafting.FurnaceRecipes", this::improveFurnaceRecipes);
        }
        if (LoliLoadingPlugin.isClient && data.optimizeBitsOfRendering) {
            addTransformation("net.minecraft.client.renderer.RenderGlobal", bytes -> fixEnumFacingValuesClone(bytes, LoliLoadingPlugin.isDeobf ? "setupTerrain" : "func_174970_a"));
        }
    }

    public void addTransformation(String key, Function<byte[], byte[]> value) {
        LoliLogger.instance.info("Adding class {} to the transformation queue", key);
        transformations.put(key, value);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        Function<byte[], byte[]> getBytes = transformations.get(transformedName);
        if (getBytes != null) {
            return getBytes.apply(bytes);
        }
        return bytes;
    }

    private byte[] canonizeResourceLocationStrings(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && method.desc.equals("(I[Ljava/lang/String;)V")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == GETSTATIC) {
                        LoliLogger.instance.info("Injecting calls in {} to canonize strings", node.name);
                        iter.remove(); // Remove GETSTATIC
                        iter.next(); // Move to INVOKEVIRTUAL, set replaces it
                        iter.set(new MethodInsnNode(INVOKESTATIC, "zone/rong/loliasm/api/StringPool", "lowerCaseAndCanonize", "(Ljava/lang/String;)Ljava/lang/String;", false));
                        break;
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] canonicalBoolConditions(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction instanceof TypeInsnNode && instruction.getOpcode() == NEW) {
                        boolean bool = ((TypeInsnNode) instruction).desc.endsWith("$1");
                        LoliLogger.instance.info("Canonizing {} IConditions", bool ? "TRUE" : "FALSE");
                        iter.remove(); // Remove NEW
                        iter.next();
                        iter.remove(); // Remove DUP
                        iter.next();
                        iter.remove(); // Remove INVOKESPECIAL
                        iter.add(new FieldInsnNode(GETSTATIC, "zone/rong/loliasm/client/models/conditions/CanonicalConditions", bool ? "TRUE" : "FALSE", "Lnet/minecraft/client/renderer/block/model/multipart/ICondition;"));
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);

        return writer.toByteArray();
    }

    private byte[] canonicalPredicatedConditions(byte[] bytes, boolean or) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        final String getPredicate = LoliLoadingPlugin.isDeobf ? "getPredicate" : "func_188118_a";

        for (MethodNode method : node.methods) {
            if (method.name.equals(getPredicate)) {
                final String conditions = LoliLoadingPlugin.isDeobf ? "conditions" : or ? "field_188127_c" : "field_188121_c";
                LoliLogger.instance.info("Transforming {}::getPredicate to canonize different IConditions", node.name);
                method.instructions.clear();
                method.instructions.add(new VarInsnNode(ALOAD, 0));
                method.instructions.add(new FieldInsnNode(GETFIELD, node.name, conditions, "Ljava/lang/Iterable;"));
                method.instructions.add(new VarInsnNode(ALOAD, 1));
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, "zone/rong/loliasm/client/models/conditions/CanonicalConditions", or ? "orCache" : "andCache", "(Ljava/lang/Iterable;Lnet/minecraft/block/state/BlockStateContainer;)Lcom/google/common/base/Predicate;", false));
                method.instructions.add(new InsnNode(ARETURN));
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);

        return writer.toByteArray();
    }

    private byte[] canonicalPropertyValueConditions(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        final String getPredicate = LoliLoadingPlugin.isDeobf ? "getPredicate" : "func_188118_a";

        for (MethodNode method : node.methods) {
            if (method.name.equals(getPredicate)) {
                LoliLogger.instance.info("Transforming {}::getPredicate to canonize different PropertyValueConditions", node.name);
                method.instructions.clear();
                method.instructions.add(new VarInsnNode(ALOAD, 1));
                method.instructions.add(new VarInsnNode(ALOAD, 0));
                method.instructions.add(new FieldInsnNode(GETFIELD, node.name, LoliLoadingPlugin.isDeobf ? "key" : "field_188125_d", "Ljava/lang/String;"));
                method.instructions.add(new VarInsnNode(ALOAD, 0));
                method.instructions.add(new FieldInsnNode(GETFIELD, node.name, LoliLoadingPlugin.isDeobf ? "value" : "field_188126_e", "Ljava/lang/String;"));
                method.instructions.add(new FieldInsnNode(GETSTATIC, node.name, LoliLoadingPlugin.isDeobf ? "SPLITTER" : "field_188124_c", "Lcom/google/common/base/Splitter;"));
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, "zone/rong/loliasm/client/models/conditions/CanonicalConditions", "propertyValueCache", "(Lnet/minecraft/block/state/BlockStateContainer;Ljava/lang/String;Ljava/lang/String;Lcom/google/common/base/Splitter;)Lcom/google/common/base/Predicate;", false));
                method.instructions.add(new InsnNode(ARETURN));
                // method.localVariables.remove(0);
                method.localVariables.clear();
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);

        return writer.toByteArray();
    }

    private byte[] cacheMultipartBakedModels(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        final String makeMultipartModel = LoliLoadingPlugin.isDeobf ? "makeMultipartModel" : "func_188647_a";

        for (MethodNode method : node.methods) {
            if (method.name.equals(makeMultipartModel)) {
                LoliLogger.instance.info("Transforming {}::makeMultipartModel", node.name);
                final String builderSelectors = LoliLoadingPlugin.isDeobf ? "builderSelectors" : "field_188649_a";
                method.instructions.clear();
                method.instructions.add(new VarInsnNode(ALOAD, 0));
                method.instructions.add(new FieldInsnNode(GETFIELD, node.name, builderSelectors, "Ljava/util/Map;"));
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, "zone/rong/loliasm/client/models/MultipartBakedModelCache", "makeMultipartModel", "(Ljava/util/Map;)Lnet/minecraft/client/renderer/block/model/MultipartBakedModel;", false));
                method.instructions.add(new InsnNode(ARETURN));
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] removeValuesArrayFromRegistrySimple(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        final String values = LoliLoadingPlugin.isDeobf ? "values" : "field_186802_b";

        node.fields.removeIf(f -> f.name.equals(values));

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] removeDupeMapFromSoundRegistry(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        final String soundRegistry = LoliLoadingPlugin.isDeobf ? "soundRegistry" : "field_148764_a";

        node.fields.removeIf(f -> f.name.equals(soundRegistry));

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] improveFurnaceRecipes(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                boolean isExperienceList = false;
                LoliLogger.instance.info("Improving FurnaceRecipes. Lookups are now a lot faster.");
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode methodInstruction = (MethodInsnNode) instruction;
                        if (methodInstruction.owner.equals("com/google/common/collect/Maps")) {
                            iter.remove();
                            if (!isExperienceList) {
                                iter.add(new TypeInsnNode(NEW, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenCustomHashMap"));
                                iter.add(new InsnNode(DUP));
                                iter.add(new FieldInsnNode(GETSTATIC, "zone/rong/loliasm/api/HashingStrategies", "ITEM_AND_META_HASH", "Lit/unimi/dsi/fastutil/Hash$Strategy;"));
                                iter.add(new MethodInsnNode(INVOKESPECIAL, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenCustomHashMap", "<init>", "(Lit/unimi/dsi/fastutil/Hash$Strategy;)V", false));
                                isExperienceList = true;
                            } else {
                                iter.add(new TypeInsnNode(NEW, "it/unimi/dsi/fastutil/objects/Object2FloatArrayMap"));
                                iter.add(new InsnNode(DUP));
                                iter.add(new MethodInsnNode(INVOKESPECIAL, "it/unimi/dsi/fastutil/objects/Object2FloatArrayMap", "<init>", "()V", false));
                                iter.next();
                                iter.add(new VarInsnNode(ALOAD, 0));
                                iter.add(new FieldInsnNode(GETFIELD, "net/minecraft/item/crafting/FurnaceRecipes", LoliLoadingPlugin.isDeobf ? "experienceList" : "field_77605_c", "Ljava/util/Map;"));
                                iter.add(new TypeInsnNode(CHECKCAST, "it/unimi/dsi/fastutil/objects/Object2FloatFunction"));
                                iter.add(new LdcInsnNode(1F));
                                iter.add(new MethodInsnNode(INVOKEINTERFACE, "it/unimi/dsi/fastutil/objects/Object2FloatFunction", "defaultReturnValue", "(F)V", true));
                            }
                        }
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] removeInstancedRandom(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        node.fields.removeIf(f -> f.desc.equals("Ljava/util/Random;"));

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction instanceof TypeInsnNode) {
                        TypeInsnNode newNode = (TypeInsnNode) instruction;
                        if (newNode.desc.equals("java/util/Random")) {
                            iter.previous();
                            iter.remove(); // Remove ALOAD
                            iter.next();
                            iter.remove(); // Remove NEW
                            iter.next();
                            iter.remove(); // Remove DUP
                            iter.next();
                            iter.remove(); // Remove INVOKESPECIAL
                            iter.next();
                            iter.remove(); // Remove PUTFIELD
                        }
                    }
                }
            } else if (method.name.equals(LoliLoadingPlugin.isDeobf ? "cloneEntry" : "func_148720_g")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == GETFIELD) {
                        if (((FieldInsnNode) instruction).desc.equals("Ljava/util/Random;")) {
                            iter.previous();
                            iter.remove(); // Remove ALOAD
                            iter.next();
                            iter.remove(); // Remove GETFIELD
                            iter.next();
                            iter.remove(); // Remove ILOAD
                            iter.add(new MethodInsnNode(INVOKESTATIC, "java/util/concurrent/ThreadLocalRandom", "current", "()Ljava/util/concurrent/ThreadLocalRandom;", false));
                            iter.add(new IntInsnNode(ILOAD, 1));
                            iter.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/concurrent/ThreadLocalRandom", "nextInt", "(I)I", false));
                        }
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] nbtTagCompound$replaceDefaultHashMap(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == INVOKESTATIC) {
                        iter.set(new TypeInsnNode(NEW, "it/unimi/dsi/fastutil/objects/Object2ObjectArrayMap"));
                        iter.add(new InsnNode(DUP));
                        iter.add(new MethodInsnNode(INVOKESPECIAL, "it/unimi/dsi/fastutil/objects/Object2ObjectArrayMap", "<init>", "()V", false));
                        break;
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] fixEnumFacingValuesClone(byte[] bytes, String methodMatch) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.equals(methodMatch)) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == INVOKESTATIC) {
                        MethodInsnNode methodInsnNode = (MethodInsnNode) instruction;
                        if (methodInsnNode.name.equals("values") && methodInsnNode.desc.equals("()[Lnet/minecraft/util/EnumFacing;")) {
                            LoliLogger.instance.info("Transforming EnumFacing::values() to EnumFacing::VALUES in {}", node.name);
                            iter.set(new FieldInsnNode(GETSTATIC, "net/minecraft/util/EnumFacing", LoliLoadingPlugin.isDeobf ? "VALUES" : "field_82609_l", "[Lnet/minecraft/util/EnumFacing;"));
                        }
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] removePackageField(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        node.fields.removeIf(f -> f.name.equals("packages"));

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && method.desc.equals("(Ljava/io/File;Ljava/io/File;Lnet/minecraftforge/fml/common/discovery/ContainerType;ZZ)V")) {
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == PUTFIELD) {
                        FieldInsnNode fieldNode = (FieldInsnNode) instruction;
                        if (fieldNode.name.equals("packages")) {
                            iter.remove(); // PUTFIELD
                            iter.previous();
                            iter.remove(); // INVOKESTATIC
                            iter.previous();
                            iter.remove(); // ALOAD
                            iter.previous();
                            iter.remove(); // LINENUMBER
                            iter.previous();
                            iter.remove(); // LABEL
                        }
                    }
                }
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }
}
