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
 * Get the contacts and distributions lists Collection for the account,
 * for use within the zimlet's iFrame
 *
 * <GetContactsRequest xmlns="urn:crunchmail" [debug="0|1"] />
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
 */
public class GetContacts extends DocumentHandler {

    // private boolean asTree;
    private Logger logger;
    private ResponseHelpers helpers = new ResponseHelpers();

    private void handleContacts(Mailbox mbox, Account account, Element response, boolean debug) throws ServiceException {
        ContactsFetcher fetcher = new ContactsFetcher(mbox, account, debug);
        Map<String, List<HashMap<String, Object>>> collection = fetcher.fetchCollection();

        List<HashMap<String, Object>> contactsCollection = collection.get("contacts");
        if (contactsCollection.isEmpty()) {
            // add it anyway so client doesn't have to test
            response.addNonUniqueElement("contacts");
        } else {
            for (Map<String, Object> contact : contactsCollection) {
                Element c = response.addNonUniqueElement("contacts");
                helpers.makeContactElement(c, contact);
            }
        }

        List<HashMap<String, Object>> groupsCollection = collection.get("groups");
        if (groupsCollection.isEmpty()) {
            // add it anyway so client doesn't have to test
            response.addNonUniqueElement("groups");
        } else {
            for (Map<String, Object> group : groupsCollection) {
                Element g = response.addNonUniqueElement("groups");
                helpers.makeGroupElement(g, group);
            }
        }
    }

    private void handleLists(Account account, Element response, boolean debug) throws ServiceException {
        ListsFetcher fetcher = new ListsFetcher(account, debug);
        List<HashMap<String, Object>> collection = fetcher.fetch();

        for (HashMap<String, Object> list : collection) {
            Element l = response.addNonUniqueElement("dls");

            l.addAttribute("id", (String) list.get("id"));
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

        Element response = zsc.createElement("GetContactsResponse");

        handleContacts(mbox, account, response, debug);
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
