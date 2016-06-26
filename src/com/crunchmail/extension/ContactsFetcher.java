package com.crunchmail.extension;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.mailbox.Mailbox;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.ContactsCrawler;
import com.crunchmail.extension.ContactsCrawler.Collection;
import com.crunchmail.extension.ContactsCrawler.Tree;

/**
 *
 */
public class ContactsFetcher extends ContactsCrawler {

    public ContactsFetcher(Mailbox mbox, Account account) throws ServiceException {
        super(mbox, account, false, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug) throws ServiceException {
        super(mbox, account, debug, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug, String[] includeFieldsDefault) throws ServiceException {
        super(mbox, account, debug, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug, String[] includeFieldsDefault, boolean forceConsiderShared) throws ServiceException {
        super(mbox, account, debug, includeFieldsDefault, forceConsiderShared);
    }

    public Collection fetchCollection() throws ServiceException {
        ItemId root = new ItemId(mMbox.getAccount().getId(), Mailbox.ID_FOLDER_USER_ROOT);
        return fetchCollection(root);
    }

    public Collection fetchCollection(ItemId root) throws ServiceException {
        crawl(root, false);
        return mCollection;
    }

    public Tree fetchTree() throws ServiceException {
        ItemId root = new ItemId(mMbox.getAccount().getId(), Mailbox.ID_FOLDER_USER_ROOT);
        return fetchTree(root);
    }

    public Tree fetchTree(ItemId root) throws ServiceException {
        crawl(root, true);
        return mTree;
    }

}
