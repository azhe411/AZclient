package com.lite.agent;

import java.lang.reflect.*;
import org.objectweb.asm.*;

/** Extends only manual entity picking. Vanilla still owns clicks, cooldowns and attack packets. */
public final class ReachPicker {
    private ReachPicker() {}
    static boolean modernEnabled, modernSprintOnly;
    static double modernRange = 3;
    static String error = "";
    private static Binding binding;

    /** No local-slot assumptions: run after every normal return of vanilla's complete pick. */
    static byte[] transform(byte[] input, final String method) {
        final int[] count = {0, 0};
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(LiteAgent.ASM_API, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor out = super.visitMethod(access, name, desc, signature, exceptions);
                if (!method.equals(name) || !"(F)V".equals(desc)) return out;
                count[0]++;
                return new MethodVisitor(LiteAgent.ASM_API, out) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            count[1]++;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.FLOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/ReachPicker",
                                    "afterPick", "(Ljava/lang/Object;F)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (count[0] != 1 || count[1] == 0)
            throw new IllegalStateException("Reach picking mapping mismatch: " + method);
        LiteAgent.log("Reach: " + method + " connected at " + count[1] + " return sites");
        return writer.toByteArray();
    }

    public static void afterPick(Object renderer, float partial) {
        if (!Hook.reachEnabled()) return;
        try {
            if (binding == null) binding = new Binding();
            Binding b = binding;
            Object mc = b.getMc.invoke(null), world = b.world.get(mc), player = b.player.get(mc);
            if (world == null || player == null || b.screen.get(mc) != null) return;
            Object camera = b.camera.invoke(mc);
            // A detached/free camera must never become the interaction origin.
            if (camera != player) return;
            boolean sprinting = Boolean.TRUE.equals(b.sprint.invoke(player));
            double range = Hook.reachRange(sprinting);
            if (!(range >= 2 && range <= 6) || !Double.isFinite(range)) return;
            Object eye = b.eye.invoke(camera, partial), look = b.look.invoke(camera, partial);
            double[] origin = b.vector(eye), direction = b.vector(look);
            Object block = b.trace(camera, range, partial);
            double limit = range;
            if (b.isType(block, "BLOCK")) limit = Math.min(limit, distance(origin, b.vector(b.hitPoint(block))));
            Object nearest = null;
            double best = limit;
            Iterable<?> entities = (Iterable<?>) (b.entities != null ? b.entities.invoke(world) : b.loaded.get(world));
            Object vehicle = b.vehicle(camera);
            for (Object entity : entities) {
                if (entity == camera || !Boolean.TRUE.equals(b.collidable.invoke(entity))) continue;
                if ((b.modern || b.playerClass.isInstance(entity)) && Boolean.TRUE.equals(b.spectator.invoke(entity))) continue;
                double radius = ((Number) b.border.invoke(entity)).doubleValue();
                Object box = b.box.invoke(entity);
                double hit = intersect(origin, direction, b.bounds(box), radius, range);
                if (!Double.isFinite(hit) || hit > best || (hit == best && b.isType(block, "BLOCK"))) continue;
                if (hit > 0 && vehicle != null && b.sameVehicle(entity, vehicle)
                        && (b.riderInteract == null || !Boolean.TRUE.equals(b.riderInteract.invoke(entity)))) continue;
                if (nearest != null && hit == best) continue;
                best = hit; nearest = entity;
            }
            // Keep excluded entities as occluders; never extend a pick through them.
            if(EntityExclusions.contains(nearest))return;
            Object original = b.hit.get(mc);
            if (nearest != null) {
                Object point = b.vec.newInstance(origin[0]+direction[0]*best,
                        origin[1]+direction[1]*best, origin[2]+direction[2]*best);
                Object result = b.entityHit.newInstance(nearest, point);
                b.hit.set(mc, result);
                b.pointed.set(mc, b.living.isInstance(nearest) || b.itemFrame.isInstance(nearest) ? nearest : null);
                if (b.rendererPointed != null) b.rendererPointed.set(renderer, nearest);
            } else if (b.isType(original, "ENTITY")) {
                // A smaller configured range must also remove a vanilla-selected entity.
                Object controller = b.controller.get(mc);
                double normal = ((Number) b.blockRange.invoke(controller)).doubleValue();
                b.hit.set(mc, b.trace(camera, normal, partial));
                b.pointed.set(mc, null);
                if (b.rendererPointed != null) b.rendererPointed.set(renderer, null);
            }
            error = "";
        } catch (Throwable problem) {
            while (problem instanceof InvocationTargetException && problem.getCause() != null) problem = problem.getCause();
            String message = problem.toString();
            if (!message.equals(error)) LiteAgent.log("Reach picking failed: " + message);
            error = message;
        }
    }

    static double distance(double[] a, double[] b) {
        double x=a[0]-b[0], y=a[1]-b[1], z=a[2]-b[2];
        return Math.sqrt(x*x+y*y+z*z);
    }

    /** Ray/AABB entry distance; origin and view vector come from vanilla interpolation. */
    static double intersect(double[] origin, double[] direction, double[] bounds, double border, double range) {
        if (!Double.isFinite(border) || border < 0 || !Double.isFinite(range) || range < 0) return Double.POSITIVE_INFINITY;
        double near=0, far=range;
        for (int i=0;i<3;i++) {
            double lo=bounds[i]-border, hi=bounds[i+3]+border, o=origin[i], d=direction[i];
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || !Double.isFinite(o) || !Double.isFinite(d) || lo>hi)
                return Double.POSITIVE_INFINITY;
            if (Math.abs(d)<1e-12) { if(o<lo || o>hi) return Double.POSITIVE_INFINITY; }
            else {
                double a=(lo-o)/d, b=(hi-o)/d;
                near=Math.max(near,Math.min(a,b)); far=Math.min(far,Math.max(a,b));
                if(near>far) return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }

    /** Strict version-specific API binding. Nothing silently degrades to a nearby-target attack. */
    static final class Binding {
        final boolean modern = Ver.is1201();
        final Method getMc, camera, eye, look, sprint, collidable, spectator, border, box, pick,
                entities, hitType, hitLocation, rootVehicle, riderInteract, blockRange;
        final Field world, player, screen, hit, pointed, loaded, type, hitVec, rendererPointed, riding, controller;
        final Field[] xyz, bb;
        final Class<?> playerClass, living, itemFrame;
        final Constructor<?> vec, entityHit;
        Binding() throws Exception {
            Class<?> mc=cls("net.minecraft.client.Minecraft"), entity=cls(modern?"net.minecraft.world.entity.Entity":"net.minecraft.entity.Entity");
            Class<?> vector=cls(modern?"net.minecraft.world.phys.Vec3":Ver.is189()?"net.minecraft.util.Vec3":"net.minecraft.util.math.Vec3d");
            Class<?> aabb=cls(modern?"net.minecraft.world.phys.AABB":Ver.is189()?"net.minecraft.util.AxisAlignedBB":"net.minecraft.util.math.AxisAlignedBB");
            Class<?> result=cls(modern?"net.minecraft.world.phys.HitResult":Ver.is189()?"net.minecraft.util.MovingObjectPosition":"net.minecraft.util.math.RayTraceResult");
            playerClass=cls(modern?"net.minecraft.world.entity.player.Player":"net.minecraft.entity.player.EntityPlayer");
            living=cls(modern?"net.minecraft.world.entity.LivingEntity":"net.minecraft.entity.EntityLivingBase");
            itemFrame=cls(modern?"net.minecraft.world.entity.decoration.ItemFrame":"net.minecraft.entity.item.EntityItemFrame");
            getMc=mc.getMethod(modern?"m_91087_":"func_71410_x");
            camera=mc.getMethod(modern?"m_91288_":"func_175606_aa");
            world=mc.getField(modern?"f_91073_":"field_71441_e"); player=mc.getField(modern?"f_91074_":"field_71439_g");
            screen=mc.getField(modern?"f_91080_":"field_71462_r"); hit=mc.getField(modern?"f_91077_":"field_71476_x");
            pointed=mc.getField(modern?"f_91076_":"field_147125_j");
            controller=mc.getField(modern?"f_91072_":"field_71442_b");
            blockRange=controller.getType().getMethod(modern?"m_105286_":"func_78757_d");
            eye=entity.getMethod(modern?"m_20299_":"func_174824_e",float.class);
            look=entity.getMethod(modern?"m_20252_":"func_70676_i",float.class);
            sprint=entity.getMethod(modern?"m_20142_":"func_70051_ag");
            collidable=entity.getMethod(modern?"m_6087_":"func_70067_L");
            spectator=(modern?entity:playerClass).getMethod(modern?"m_5833_":"func_175149_v");
            border=entity.getMethod(modern?"m_6143_":"func_70111_Y"); box=entity.getMethod(modern?"m_20191_":"func_174813_aQ");
            pick=modern?entity.getMethod("m_19907_",double.class,float.class,boolean.class):entity.getMethod("func_174822_a",double.class,float.class);
            entities=modern?world.getType().getMethod("m_104735_"):null;
            loaded=modern?null:world.getType().getField("field_72996_f");
            hitType=modern?result.getMethod("m_6662_"):null; type=modern?null:result.getField("field_72313_a");
            hitLocation=modern?result.getMethod("m_82450_"):null; hitVec=modern?null:result.getField("field_72307_f");
            xyz=fields(vector,modern?new String[]{"f_82479_","f_82480_","f_82481_"}:new String[]{"field_72450_a","field_72448_b","field_72449_c"});
            bb=fields(aabb,modern?new String[]{"f_82288_","f_82289_","f_82290_","f_82291_","f_82292_","f_82293_"}:
                    new String[]{"field_72340_a","field_72338_b","field_72339_c","field_72336_d","field_72337_e","field_72334_f"});
            vec=vector.getConstructor(double.class,double.class,double.class);
            entityHit=(modern?cls("net.minecraft.world.phys.EntityHitResult"):result).getConstructor(entity,vector);
            rendererPointed=modern?null:field(cls("net.minecraft.client.renderer.EntityRenderer"),"field_78528_u");
            rootVehicle=Ver.is189()?null:entity.getMethod(modern?"m_20201_":"func_184208_bv");
            riding=Ver.is189()?entity.getField("field_70154_o"):null;
            Method rider=null;try{rider=entity.getMethod("canRiderInteract");}catch(NoSuchMethodException ignored){}
            riderInteract=rider;
        }
        Object trace(Object camera,double range,float partial)throws Exception{return modern?pick.invoke(camera,range,partial,false):pick.invoke(camera,range,partial);}
        Object hitPoint(Object result)throws Exception{return modern?hitLocation.invoke(result):hitVec.get(result);}
        boolean isType(Object result,String name)throws Exception{return result!=null && name.equals(String.valueOf(modern?hitType.invoke(result):type.get(result)));}
        double[] vector(Object v)throws Exception{return values(xyz,v);}
        double[] bounds(Object b)throws Exception{return values(bb,b);}
        Object vehicle(Object entity)throws Exception{return rootVehicle != null ? rootVehicle.invoke(entity) : riding.get(entity);}
        boolean sameVehicle(Object entity,Object vehicle)throws Exception{return rootVehicle != null ? rootVehicle.invoke(entity)==vehicle : entity==vehicle;}
        static Class<?> cls(String name)throws Exception{return Class.forName(name,false,Ver.runtimeLoader()!=null?Ver.runtimeLoader():ReachPicker.class.getClassLoader());}
        static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
        static Field[] fields(Class<?> c,String[] names)throws Exception{Field[] f=new Field[names.length];for(int i=0;i<f.length;i++)f[i]=c.getField(names[i]);return f;}
        static double[] values(Field[] f,Object o)throws Exception{double[] v=new double[f.length];for(int i=0;i<v.length;i++)v[i]=f[i].getDouble(o);return v;}
    }
}
