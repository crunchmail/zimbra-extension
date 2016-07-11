package com.crunchmail.extension.soap.handlers;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
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
import com.crunchmail.extension.ListsFetcher;
import com.crunchmail.extension.ListsFetcher.ListsCollection;

public abstract class AbstractGetContactsHandler extends DocumentHandler {

    private Logger mLogger;

    abstract Element fetchContacts(ZimbraSoapContext zsc, Mailbox mbox, Account account, boolean debug, Set<String> existing) throws ServiceException;

    /**
     * Handle the SOAP the request
     * @param Element request The request
     * @param MapwString, Object> context The soap context
     * @return Element response
     * @throws ServiceException
     */
    @Override
    public Element handle(Element request, Map<String, Object> context) throws ServiceException {

        boolean debug = request.getAttributeBool("debug", false);
        mLogger = new Logger(debug);

        List<String> ex = new ArrayList<String>();
        for (Element el : request.listElements("existing")) {
            try {
                ex.add(el.getAttribute("ref"));
            } catch (ServiceException e) {
                if (!e.getCode().equals(ServiceException.INVALID_REQUEST)) throw e;
            }
        }
        // Remove duplicates
        Set<String> existing = new HashSet<String>(ex);

        // We time fetch exec time to return it to the client
        Stopwatch timer = new Stopwatch().start();

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Mailbox mbox = getRequestedMailbox(zsc);
        Account account = mbox.getAccount();

        Element response = fetchContacts(zsc, mbox, account, debug, existing);

        ListsFetcher listsFetcher = new ListsFetcher(account, debug, existing);
        ListsCollection listsCollection = listsFetcher.fetch();
        listsCollection.toElement(response);

        Element el;
        try {
            el = response.getElement("existing");
        } catch (ServiceException e) {
            el = response.addUniqueElement("existing");
        }
        listsFetcher.mExistingCollection.toElement(el);

        if (existing.isEmpty()) {
            // add empty element so client doesn't have to test
            response.addUniqueElement("remaining");
        } else {
            for (String remaining : existing) {
                Element r = response.addNonUniqueElement("remaining");
                r.addAttribute("ref", remaining);
            }
        }

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
