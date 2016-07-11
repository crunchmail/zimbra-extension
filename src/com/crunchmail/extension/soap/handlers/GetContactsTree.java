package com.crunchmail.extension.soap.handlers;

import java.util.Set;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.soap.ZimbraSoapContext;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.mailbox.Mailbox;

import com.crunchmail.extension.soap.handlers.AbstractGetContactsHandler;
import com.crunchmail.extension.ContactsFetcher;
import com.crunchmail.extension.ContactsCrawler.Tree;


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
public class GetContactsTree extends AbstractGetContactsHandler {

    @Override
    Element fetchContacts(ZimbraSoapContext zsc, Mailbox mbox, Account account, boolean debug, Set<String> existing) throws ServiceException {
        Element response = zsc.createElement("GetContactsTreeResponse");

        ContactsFetcher contactsFetcher = new ContactsFetcher(mbox, account, debug, existing);
        Tree contactsTree = contactsFetcher.fetchTree();
        contactsTree.toElement(response);

        Element el = response.addUniqueElement("existing");
        contactsFetcher.mExistingCollection.toElement(el);

        return response;
    }

}
