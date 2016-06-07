package com.crunchmail.extension.soap.handlers;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import static com.zimbra.common.soap.Element.Attribute;
import com.zimbra.common.soap.SoapParseException;
import com.zimbra.soap.DocumentHandler;
import com.zimbra.soap.ZimbraSoapContext;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Tag;
import com.zimbra.cs.mailbox.OperationContext;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.ContactsFetcher;
import com.crunchmail.extension.ListsFetcher;

/**
 * Get the contact and distributions lists for the account,
 * formatted for use within the zimlet's iFrame
 *
 * <GetContactsRequest xmlns="urn:crunchmail" />
 *
 * <GetContactsResponse>
 *   (<contacts email="contact-email" name="contact-name"
 *              sourceRef="contact:AccountId:ContactId"
 *              sourceType="zimbra">
 *        <properties [firstName=""] [lastName=""] [...] />
 *       ([<tags name="tag-name"/>])*
 *   </contacts>)*
 *   (<groups name="group-name">
 *       (<members email="contact-email" sourceType="zimbra"
 *                 sourceRef="group:AccountId:GroupId">
 *           <properties [firstName=""] [lastName=""] [...] />
 *       </members>)*
 *      ([<tags name="tag-name"/>])*
 *   </groups>)*
 *   (<dls name="group-name">
 *       (<members email="contact-email" sourceType="zimbra"
 *                 sourceRef="dl:list-email">
 *           <properties [firstName=""] [lastName=""] />
 *       </members>)*
 *   </dls>)*
 *   (<tags name="tag-name" color="HEX color" />)*
 * </GetContactsResponse>
 *
 * If the date differed, something went wrong.
 */
public class GetContacts extends DocumentHandler {

    // private boolean asTree;
    private Logger logger = new Logger();

    private Element makeContactElement(Element e, Map<String, Object> contact) {
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
            String[] tags = (String[]) contact.get("tags");
            for (String tag : tags) {
                Element t = e.addNonUniqueElement("tags");
                t.addAttribute("name", tag);
            }
        }

        return e;
    }

    private void handleContacts(Mailbox mbox, Account account, Element response) throws ServiceException {
        ContactsFetcher fetcher = new ContactsFetcher(mbox, account);
        Map<String, List<HashMap<String, Object>>> collection = fetcher.fetch();

        List<HashMap<String, Object>> contactsCollection = collection.get("contacts");
        for (HashMap<String, Object> contact : contactsCollection) {
            Element c = response.addNonUniqueElement("contacts");
            makeContactElement(c, contact);
        }

        List<HashMap<String, Object>> groupsCollection = collection.get("groups");
        for (HashMap<String, Object> group : groupsCollection) {
            Element g = response.addNonUniqueElement("groups");

            g.addAttribute("name", (String) group.get("name"));

            @SuppressWarnings("unchecked")
            List<HashMap<String, Object>> members = (List<HashMap<String, Object>>) group.get("members");
            for (HashMap<String, Object> contact : members) {
                Element m = g.addNonUniqueElement("members");
                makeContactElement(m, contact);
            }

            if (group.containsKey("tags")) {
                String[] tags = (String[]) group.get("tags");
                for (String tag : tags) {
                    Element t = g.addNonUniqueElement("tags");
                    t.addAttribute("name", tag);
                }
            }
        }
    }

    private void handleLists(Account account, Element response) throws ServiceException {
        ListsFetcher fetcher = new ListsFetcher(account);
        List<HashMap<String, Object>> collection = fetcher.fetch();

        for (HashMap<String, Object> list : collection) {
            Element l = response.addNonUniqueElement("dls");

            l.addAttribute("name", (String) list.get("name"));
            l.addAttribute("email", (String) list.get("email"));

            @SuppressWarnings("unchecked")
            Set<HashMap<String, Object>> members = (Set<HashMap<String, Object>>) list.get("members");
            for (HashMap<String, Object> contact : members) {
                Element m = l.addNonUniqueElement("members");
                makeContactElement(m, contact);
            }
        }
    }

    /**
     * Handle the SOAP the request
     * @param request The request
     * @param context The soap context
     * @return The response
     * @throws ServiceException
     */
    @Override
    public Element handle(Element request, Map<String, Object> context)
        throws ServiceException {

        // asTree = request.getAttributeBool("asTree", false);

        // Element pref = request.getOptionalElement("pref");
        // if (pref != null) {
        //     Set<Attribute> attrs = pref.listAttributes();
        //     if (attrs.contains("includeShared")) {
        //         includeShared = pref.getAttributeBool("includeShared", false);
        //     }
        // }

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Mailbox mbox = getRequestedMailbox(zsc);
        Account account = mbox.getAccount();

        Element response = zsc.createElement("GetContactsResponse");

        handleContacts(mbox, account, response);
        handleLists(account, response);

        OperationContext octxt = new OperationContext(mbox);
        List<Tag> tags = mbox.getTagList(octxt);
        for (Tag tag : tags) {
            Element t = response.addNonUniqueElement("tags");

            t.addAttribute("name", tag.getName());
            t.addAttribute("color", tag.getRgbColor().toString());
        }

        logger.info(response.prettyPrint());

        return response;

    }
}
