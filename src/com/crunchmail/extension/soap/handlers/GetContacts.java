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
import com.crunchmail.extension.ContactsCrawler.Collection;
import com.crunchmail.extension.ListsFetcher;
import com.crunchmail.extension.ListsFetcher.ListsCollection;

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

    private Logger mLogger;

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
        mLogger = new Logger(debug);

        // We time fetch exec time to return it to the client
        Stopwatch timer = new Stopwatch().start();

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Mailbox mbox = getRequestedMailbox(zsc);
        Account account = mbox.getAccount();

        Element response = zsc.createElement("GetContactsResponse");

        ContactsFetcher contactsFetcher = new ContactsFetcher(mbox, account, debug);
        Collection contactsCollection = contactsFetcher.fetchCollection();
        contactsCollection.toElement(response);

        ListsFetcher listsFetcher = new ListsFetcher(account, debug);
        ListsCollection listsCollection = listsFetcher.fetch();
        listsCollection.toElement(response);

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
        mLogger.info("Fetched contacts in: "+timer);

        return response;

    }
}
