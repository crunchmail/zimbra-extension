package com.crunchmail.extension;

import java.util.Map;
import java.util.HashMap;

import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.cs.account.Account;

public class UserSettings {

    public static final String INCLUDE_SHARED = "contacts_include_shared";
    public static final String CONTACTS_ATTRS = "contacts_attrs";
    public static final String DLIST_MEMBER_OF = "contacts_dlist_member_of";
    public static final String DLIST_DIRECT_MEMBER_ONLY = "contacts_dlist_direct_member_only";
    public static final String DLIST_INCLUDE_HIDE_IN_GAL = "contacts_dlist_include_hide_in_gal";

    private Map<String, String> settings;

    public UserSettings(Account account) throws ServiceException {
        settings = new HashMap<String, String>();

        String[] properties = account.getZimletUserProperties();
        for (String property : properties) {
            // 1: zimlet name
            // 2: property name
            // 3: property value
            String[] elements = property.split(":", 3);
            if (elements[0].equals("com_crunchmail_zimlet") && elements.length == 3) {
                settings.put(elements[1], elements[2]);
            }

            /**
             * 			TEMP
             *  remove old settings
             */
            if (elements[0].equals("crunchmail_zimlet") || elements[0].equals("munchmail_zimlet")) {
                account.removeZimletUserProperties(property);
            }
            if (elements[0].equals("com_crunchmail_zimlet") && (elements[1].startsWith("crunchmail_") || elements[1].equals("contacts_dlist_owner_of"))) {
                account.removeZimletUserProperties(property);
            }
            /*******/
        }
    }

    public String get(String name) {
        return get(name, null);
    }

    public String get(String name, String def) {
        if (settings.containsKey(name)) {
            return settings.get(name);
        } else {
            return def;
        }
    }

    public String[] getArray(String name) {
        return getArray(name, ",", new String[0]);
    }

    public String[] getArray(String name, String delimiter) {
        return getArray(name, delimiter, new String[0]);
    }

    public String[] getArray(String name, String delimiter, String[] def) {
        if (settings.containsKey(name)) {
            return settings.get(name).split(delimiter);
        } else {
            return def;
        }
    }

    public boolean getBool(String name) {
        return getBool(name, false);
    }

    public boolean getBool(String name, boolean def) {
        if (settings.containsKey(name)) {
            try {
                boolean bool = Element.parseBool("", settings.get(name));
                return bool;
            } catch (ServiceException e) {
                return def;
            }
        } else {
            return def;
        }
    }
}
