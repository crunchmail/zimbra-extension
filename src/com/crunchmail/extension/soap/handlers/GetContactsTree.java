package com.crunchmail.extension.soap.handlers;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import com.google.common.base.Stopwatch;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
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
import com.crunchmail.extension.soap.ResponseHelpers;


/**
 * Get the contacts and distributions lists Tree for the account,
 * use within the zimlet's iFrame
 *
 * <GetContactsTreeRequest xmlns="urn:crunchmail" [debug="0|1"] />
 *
 * <GetContactsTreeResponse>
 *   TREE
 *   (<dls name="group-name">
 *       (<members email="contact-email" sourceType="zimbra"
 *                 sourceRef="dl:list-email">
 *           <properties [firstName=""] [lastName=""] />
 *       </members>)*
 *   </dls>)*
 *   (<tags name="tag-name" color="HEX color" />)*
 * </GetContactsTreeResponse>
 *
 */
public class GetContactsTree extends DocumentHandler {

    // private boolean asTree;
    private Logger logger;
    private ResponseHelpers helpers = new ResponseHelpers();

    private void recurseTree(Element el, String name, HashMap<String, Object> entry) throws ServiceException {
        Element f = el.addUniqueElement(name);

        @SuppressWarnings("unchecked")
        Set<HashMap<String, Object>> contacts = (Set<HashMap<String, Object>>) entry.get("contacts");
        if (contacts.isEmpty()) {
            // add it anyway so client doesn't have to test
            f.addNonUniqueElement("contacts");
        } else {
            for (HashMap<String, Object> contact : contacts) {
                Element c = f.addNonUniqueElement("contacts");
                helpers.makeContactElement(c, contact);
            }
        }

        @SuppressWarnings("unchecked")
        Set<HashMap<String, Object>> groups = (Set<HashMap<String, Object>>) entry.get("groups");
        if (groups.isEmpty()) {
            // add it anyway so client doesn't have to test
            f.addNonUniqueElement("groups");
        } else {
            for (HashMap<String, Object> group : groups) {
                Element g = f.addNonUniqueElement("groups");
                helpers.makeGroupElement(g, group);
            }
        }

        Element a = f.addUniqueElement("_attrs");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) entry.get("_attrs");
        for (Map.Entry<String, Object> attr : attrs.entrySet()) {
            if (attr.getValue() instanceof String) {
                a.addAttribute(attr.getKey(), (String) attr.getValue());
            } else if (attr.getValue() instanceof Boolean) {
                a.addAttribute(attr.getKey(), (Boolean) attr.getValue());
            }
        }

        Element s = f.addUniqueElement("_subfolders");

        @SuppressWarnings("unchecked")
        HashMap<String, Object> subfolders = (HashMap<String, Object>) entry.get("_subfolders");
        for (Map.Entry<String, Object> subfolder : subfolders.entrySet()) {
            @SuppressWarnings("unchecked")
            HashMap<String, Object> value = (HashMap<String, Object>) subfolder.getValue();
            recurseTree(s, subfolder.getKey(), value);
        }
    }

    private void handleTree(Mailbox mbox, Account account, Element response, boolean debug) throws ServiceException {
        ContactsFetcher fetcher = new ContactsFetcher(mbox, account, debug);
        Map<String, Object> tree = fetcher.fetchTree();

        Element t = response.addUniqueElement("tree");

        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            @SuppressWarnings("unchecked")
            HashMap<String, Object> value = (HashMap<String, Object>) entry.getValue();
            recurseTree(t, entry.getKey(), value);
        }
    }

    private void handleLists(Account account, Element response, boolean debug) throws ServiceException {
        ListsFetcher fetcher = new ListsFetcher(account, debug);
        List<HashMap<String, Object>> collection = fetcher.fetch();

        for (HashMap<String, Object> list : collection) {
            Element l = response.addNonUniqueElement("dls");

            l.addAttribute("name", (String) list.get("name"));
            l.addAttribute("email", (String) list.get("email"));

            @SuppressWarnings("unchecked")
            Set<HashMap<String, Object>> members = (Set<HashMap<String, Object>>) list.get("members");
            for (HashMap<String, Object> contact : members) {
                Element m = l.addNonUniqueElement("members");
                helpers.makeContactElement(m, contact);
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
    public Element handle(Element request, Map<String, Object> context) throws ServiceException {

        boolean debug = request.getAttributeBool("debug", false);
        logger = new Logger(debug);

        // We time fetch exec time to return it to the client
        Stopwatch timer = new Stopwatch().start();

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Mailbox mbox = getRequestedMailbox(zsc);
        Account account = mbox.getAccount();

        Element response = zsc.createElement("GetContactsTreeResponse");

        handleTree(mbox, account, response, debug);
        handleLists(account, response, debug);

        OperationContext octxt = new OperationContext(mbox);
        List<Tag> tags = mbox.getTagList(octxt);
        for (Tag tag : tags) {
            Element t = response.addNonUniqueElement("tags");

            t.addAttribute("name", tag.getName());
            t.addAttribute("color", tag.getRgbColor().toString());
        }

        // stop timing
        timer.stop();

        response.addAttribute("timer", timer.toString());
        logger.info("Fetched contacts in: "+timer);

        return response;
    }
}
