package com.joedobo27.sgi;

import com.wurmonline.server.Players;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.skills.Skill;
import javassist.*;
import javassist.bytecode.*;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class SkillGainInformationMod implements WurmServerMod, Initable {

    static final Logger logger = Logger.getLogger(SkillGainInformationMod.class.getName());

    @Override
    public void init() {
        try {
            // 3. Insert after call to rollGaussian() in Skill.checkAdvance().
            ClassFile classFile = HookManager.getInstance().getClassPool().get("com.wurmonline.server.skills.Skill").getClassFile();
            List methods = classFile.getMethods();
            MethodInfo methodInfo = IntStream.range(0, methods.size())
                    .mapToObj(value -> (MethodInfo) methods.get(value))
                    .filter(methodInfo1 -> Objects.equals("checkAdvance", methodInfo1.getName()))
                    .findFirst()
                    .orElseThrow(NullPointerException::new);
            // 3.A Get a bytecode object that is an Invokestatic + the constant pool index for rollGaussian method.
            int classReference = getClassReferenceIndex(methodInfo.getConstPool(), "com.wurmonline.server.skills.Skill");
            Bytecode findRollGaussian = new Bytecode(methodInfo.getConstPool());
            findRollGaussian.addInvokestatic(classReference, "rollGaussian", Descriptor.ofMethod(CtPrimitiveType.floatType,
                    new CtClass[]{CtPrimitiveType.floatType, CtPrimitiveType.floatType, CtPrimitiveType.longType,
                    HookManager.getInstance().getClassPool().get("java.lang.String")}));

            long checkBytecode = byteArrayToLong(findRollGaussian.get());

            // 3.B Get the byte code index of Skill.checkAdvance() where rollGaussian() is at.
            int bytecodeIndex = 0;
            CodeIterator codeIterator = methodInfo.getCodeAttribute().iterator();
            codeIterator.begin();
            while (codeIterator.hasNext()) {
                int index = codeIterator.next();
                long bytecode = getBytecodeAtIndex(codeIterator.lookAhead() - index, index, codeIterator);
                if (bytecode == checkBytecode) {
                    bytecodeIndex = index;
                }
            }

            // 3.C look through the line number table and get the insert point.
            LineNumberAttribute lineNumberAttribute = (LineNumberAttribute) methodInfo.getCodeAttribute()
                    .getAttribute(LineNumberAttribute.tag);
            int lineNumber = lineNumberAttribute.toLineNumber(bytecodeIndex);
            int tableIndex = IntStream.range(0, lineNumberAttribute.tableLength())
                    .filter(value -> Objects.equals(lineNumberAttribute.lineNumber(value), lineNumber))
                    .findFirst()
                    .orElse(0);
            int insertLine = lineNumberAttribute.lineNumber(tableIndex + 1);

            // Insert code after rollGaussian() using the insertLine found in 3.C.
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.skills.Skill")
                    .getDeclaredMethod("checkAdvance").insertAt(insertLine,
                    "com.joedobo27.sgi.SkillGainInformationMod#printSkillInformation($0, $0.parent.id, $3, skill, $1, power);");
        } catch (NotFoundException | CannotCompileException | NullPointerException | BadBytecode e){
            logger.warning(e.getMessage());
        }
    }

    public static void printSkillInformation(Skill skill, long performerId, double bonus, double skillLevel, double difficulty, double power) {
        if (skill == null)
            return;
        Player player = Players.getInstance().getPlayerOrNull(performerId);
        if (player == null)
            return;
        double slide = (skillLevel * skillLevel * skillLevel - difficulty * difficulty * difficulty) / 50000.0f + (skillLevel - difficulty);
        double w = 30.0f - Math.abs(skillLevel - difficulty) / 4.0f;
        double mean = (w + Math.abs(slide) / 6.0f) + slide;
        player.getCommunicator().sendNormalServerMessage(String.format(
                "%1$s, bonus:%2$.3f, skill:%3$.3f, difficulty: %4$.3f; gaussian mean: %5$.3f; roll: %6$.3f",
                skill.getName(), bonus, skillLevel, difficulty, slide, power));
    }

    /**
     * Often a class's name will appear twice in the constant pool. One of the occurrence is not used as a declaring class for anything.
     * I have no idea why it's present but it can break looking up constant pool references if the unassociated one is picked. JA has a
     * built in way of finding existent references but an underlying mechanic is that a hash map uses a string class name as a key
     * in a hashMap. Two equal strings will overwrite each other in this case.
     *
     * 1. scan the constant pool and get the class references that match className.
     * 2. scan again through the constant pool looking for class associations that use the references found in #1. One of the options
     *      will have no references and illuminate that one to return the one that should be used.
     *
     * @param className String type object, uses full class name and periods.
     * @return int primitive, the address in constant pool for the class matching className.
     */
    private int getClassReferenceIndex(ConstPool constPool, String className){
        return IntStream.range(1, constPool.getSize())
                .filter(value -> constPool.getTag(value) == ConstPool.CONST_Class)
                .filter(value -> Objects.equals(Descriptor.toClassName(constPool.getClassInfoByDescriptor(value)), className))
                .filter( verifyIndex ->
                        IntStream.range(1, constPool.getSize())
                                .filter(value -> constPool.getTag(value) == ConstPool.CONST_Methodref
                                        || constPool.getTag(value) == ConstPool.CONST_Fieldref
                                        || constPool.getTag(value) == ConstPool.CONST_InterfaceMethodref)
                                .filter(value -> {
                                    boolean result = false;
                                    switch (constPool.getTag(value)) {
                                        case ConstPool.CONST_Methodref:
                                            result = constPool.getMethodrefClass(value) == verifyIndex;
                                            break;
                                        case ConstPool.CONST_Fieldref:
                                            result = constPool.getFieldrefClass(value) == verifyIndex;
                                            break;
                                        case ConstPool.CONST_InterfaceMethodref:
                                            result = constPool.getInterfaceMethodrefClass(value) == verifyIndex;
                                            break;
                                    }
                                    return result;})
                                .count() > 0
                )
                .findFirst()
                .orElse(-1);
    }

    /**
     * https://en.wikipedia.org/wiki/Java_bytecode_instruction_listings
     * @param size how many bytes is the instruction.
     * @param index where is the instruction at.
     * @param codeIterator CodeIterator object that has all the bytecode.
     * @return An encoded byte[] into long. [opcode][byte 1][byte 2]...[byte n]
     */
    private static long getBytecodeAtIndex(int size, int index, CodeIterator codeIterator) {
        int[] ints = new int[size];
        switch (size) {
            case 1:
                // Only a Opcode.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 2:
                // An Opcode + 1 byte of additional information
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 3:
                // many of these: Opcode + 2 bytes of additional information
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 4:
                // few of these: multianewarray or in some cases wide.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 5:
                // onl invokeinterface, invokedynamic, goto_w, jsr_w
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 6:
                // only a wide in some cases.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
        }
        byte[] bytes = new byte[ints.length];
        for(int i=0;i<ints.length;i++) {
            bytes[i] = (byte)ints[i];
        }
        return byteArrayToLong(bytes);
        // there is tableswitch at 16+ size and lookupswitch at 8+ size. these likely need special treatment.
    }

    private static long byteArrayToLong(byte[] bytesOriginal) {
        if (bytesOriginal.length < 8) {
            byte[] bytesLongPadded = new byte[8];
            System.arraycopy(bytesOriginal, 0, bytesLongPadded, 8 - bytesOriginal.length, bytesOriginal.length);
            return ByteBuffer.wrap(bytesLongPadded).getLong();
        }
        else
            return ByteBuffer.wrap(bytesOriginal).getLong();
    }
}
