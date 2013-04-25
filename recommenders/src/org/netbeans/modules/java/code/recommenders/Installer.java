package org.netbeans.modules.java.code.recommenders;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.openide.modules.ModuleInstall;

public class Installer extends ModuleInstall {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void validate() throws IllegalStateException {
        try {
            //XXX: hack - we need access to whitelist
            java.lang.Class main = java.lang.Class.forName("org.netbeans.core.startup.Main", false,  //NOI18N
                    Thread.currentThread().getContextClassLoader());
            Method getModuleSystem = main.getMethod("getModuleSystem", new Class[0]); //NOI18N
            Object moduleSystem = getModuleSystem.invoke(null, new Object[0]);
            Method getManager = moduleSystem.getClass().getMethod("getManager", new Class[0]); //NOI18N
            Object moduleManager = getManager.invoke(moduleSystem, new Object[0]);
            Method moduleMeth = moduleManager.getClass().getMethod("get", new Class[] {String.class}); //NOI18N
            Object persistence = moduleMeth.invoke(moduleManager, "org.netbeans.modules.whitelist"); //NOI18N
            if (persistence != null) {
                Field frField = persistence.getClass().getSuperclass().getDeclaredField("friendNames"); //NOI18N
                frField.setAccessible(true);
                Set friends = (Set)frField.get(persistence);
                friends.add("org.netbeans.modules.java.code.recommenders"); //NOI18N
            }
        } catch (Exception ex) {
            new IllegalStateException("Cannot fix dependencies for org.netbeans.modules.java.code.recommenders.", ex); //NOI18N
        }
        super.validate();
    }
}
