package com.crunchmail.extension.soap;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;

import com.zimbra.common.soap.Element;

public class ResponseHelpers {

    public ResponseHelpers() {}

    public void makeContactElement(Element e, Map<String, Object> contact) {
        // Start by inserting all plain string attributes
        for (Map.Entry<String, Object> attr : contact.entrySet()) {
            Object value = attr.getValue();
            if (value instanceof String) {
                e.addAttribute(attr.getKey(), (String) value);
            }
        }

        // Then deal with "complex" ones
        Element p = e.addUniqueElement("properties");
        if (contact.containsKey("properties")) {

            @SuppressWarnings("unchecked")
            Map<String, String> properties = (Map<String, String>) contact.get("properties");

            for (Map.Entry<String, String> property : properties.entrySet()) {
                p.addAttribute(property.getKey(), property.getValue());
            }
        }

        if (contact.containsKey("tags")) {
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) contact.get("tags");
            for (String tag : tags) {
                Element t = e.addNonUniqueElement("tags");
                t.addAttribute("name", tag);
            }
        }
    }

    public void makeGroupElement(Element e, Map<String, Object> group) {
        e.addAttribute("name", (String) group.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) group.get("members");
        for (Map<String, Object> contact : members) {
            Element m = e.addNonUniqueElement("members");
            makeContactElement(m, contact);
        }

        if (group.containsKey("tags")) {
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) group.get("tags");
            for (String tag : tags) {
                Element t = e.addNonUniqueElement("tags");
                t.addAttribute("name", tag);
            }
        }
    }
}
