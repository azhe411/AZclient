package com.lite.agent;

import org.objectweb.asm.*;

/** Render-only use-state reads and camera-relative hand transform scope. */
final class BlockAnimation {
    static final String ITEM="net/minecraft/client/renderer/ItemRenderer";
    static final String PLAYER="net/minecraft/client/renderer/entity/RenderPlayer";
    static boolean target(String name){return ITEM.equals(name)||PLAYER.equals(name);}
    static byte[] transform(String name,byte[] bytes){
        if(!target(name)||Ver.is1201())return null;
        final boolean old=Ver.is189();final int[] hooks={0};
        ClassReader r=new ClassReader(bytes);ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
        r.accept(new ClassVisitor(LiteAgent.ASM_API,w){
            @Override public MethodVisitor visitMethod(int access,String n,String d,String sig,String[] exceptions){
                MethodVisitor out=super.visitMethod(access,n,d,sig,exceptions);
                boolean render=n.equals("func_177137_d") || n.equals("func_78440_a") || (!old&&n.equals("func_187457_a"));
                if(!render)return out;
                return new MethodVisitor(LiteAgent.ASM_API,out){
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){
                        boolean itemRender=ITEM.equals(name);
                        boolean matrix=itemRender&&owner.equals("net/minecraft/client/renderer/GlStateManager")&&desc.equals("()V");
                        if(itemRender&&owner.equals(ITEM)&&(method.equals("func_178099_a")||method.equals("func_187462_a")))
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook","blockRenderDraw","()V",false);
                        if(matrix&&method.equals("func_179121_F"))
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook","blockRenderEnd","()V",false);
                        String hook=null,signature=null;
                        boolean stateReads=!itemRender||old||n.equals("func_187457_a");
                        if(stateReads&&opcode==Opcodes.INVOKEVIRTUAL&&owner.startsWith("net/minecraft/")){
                            if((method.equals("func_71052_bv")||method.equals("func_184605_cv"))&&desc.equals("()I")){
                                hook="blockRenderCount";signature="(Ljava/lang/Object;I)I";
                            }else if(method.equals("func_184587_cr")&&desc.equals("()Z")){
                                hook="blockRenderActive";signature="(Ljava/lang/Object;Z)Z";
                            }else if(method.equals("func_184600_cs")&&desc.equals("()Lnet/minecraft/util/EnumHand;")){
                                hook="blockRenderHand";signature="(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                            }
                        }
                        if(hook!=null&&itemRender)hook=hook.replace("blockRender","blockRenderItem");
                        if(hook!=null)super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(opcode,owner,method,desc,itf);
                        if(itemRender&&n.equals("func_78440_a")&&method.equals("func_70678_g")&&desc.equals("(F)F")){
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook","blockRenderSwing","(F)F",false);hooks[0]++;
                        }
                        if(matrix&&method.equals("func_179094_E")){
                            if(old)super.visitInsn(Opcodes.ACONST_NULL);else super.visitVarInsn(Opcodes.ALOAD,4);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook","blockRenderBegin","(Ljava/lang/Object;)V",false);
                        }
                        if(hook!=null){
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook",hook,signature,false);
                            if(hook.equals("blockRenderHand")||hook.equals("blockRenderItemHand"))super.visitTypeInsn(Opcodes.CHECKCAST,"net/minecraft/util/EnumHand");
                            hooks[0]++;
                        }
                    }
                };
            }
        },0);
        if(hooks[0]==0)throw new IllegalStateException("No native block animation reads in "+name);
        LiteAgent.log("Block animation render reads: "+name+" = "+hooks[0]);
        return w.toByteArray();
    }
}
