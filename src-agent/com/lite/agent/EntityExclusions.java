package com.lite.agent;

import java.lang.reflect.Method;
import java.util.*;

/** World-scoped middle-click exclusions. UUIDs survive entity object replacement. */
final class EntityExclusions {
    private static Object world;
    private static boolean middleDown, blocked = true;
    private static final Set<UUID> ids = new HashSet<UUID>();
    private static final Map<Object,Boolean> objects = new WeakHashMap<Object,Boolean>();
    private static final Map<Object,UUID> resolved = new WeakHashMap<Object,UUID>();
    private static final ClassValue<Method> uuidMethods = new ClassValue<Method>() {
        @Override protected Method computeValue(Class<?> c) {
            for (String name : new String[]{"func_110124_au", "m_20148_", "getUniqueID", "getUUID"}) {
                try { Method m=c.getMethod(name); m.setAccessible(true); return m; }
                catch (ReflectiveOperationException ignored) { }
            }
            return null;
        }
    };
    private static UUID uuid(Object entity) {
        if (resolved.containsKey(entity)) return resolved.get(entity);
        UUID id=null;
        try { Method m=uuidMethods.get(entity.getClass()); Object value=m==null?null:m.invoke(entity);
            if(value instanceof UUID)id=(UUID)value;
        } catch (ReflectiveOperationException ignored) { }
        resolved.put(entity,id); return id;
    }
    static void context(Object next) {
        if(world==next)return;
        world=next; ids.clear(); objects.clear(); resolved.clear(); blocked=true;
    }
    static boolean pressed(boolean down, boolean unavailable) {
        boolean edge=down&&!middleDown&&!blocked&&!unavailable;
        middleDown=down; blocked=unavailable; return edge;
    }
    static boolean toggle(Object entity) {
        if(entity==null)return false;
        UUID id=uuid(entity);
        if(id!=null){if(ids.remove(id))return false;ids.add(id);return true;}
        if(objects.remove(entity)!=null)return false;
        objects.put(entity,Boolean.TRUE);return true;
    }
    static boolean contains(Object entity) {
        if(entity==null || (ids.isEmpty()&&objects.isEmpty()))return false;
        return objects.containsKey(entity)||ids.contains(uuid(entity));
    }
}
