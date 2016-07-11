package com.crunchmail.extension.soap.handlers;

import java.util.Set;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.soap.ZimbraSoapContext;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.mailbox.Mailbox;

import com.crunchmail.extension.soap.handlers.AbstractGetContactsHandler;
import com.crunchmail.extension.ContactsFetcher;
import com.crunchmail.extension.ContactsCrawler.Collection;

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
public class GetContacts extends AbstractGetContactsHandler {

    @Override
    Element fetchContacts(ZimbraSoapContext zsc, Mailbox mbox, Account account, boolean debug, Set<String> existing) throws ServiceException {
        Element response = zsc.createElement("GetContactsResponse");

        ContactsFetcher contactsFetcher = new ContactsFetcher(mbox, account, debug, existing);
        Collection contactsCollection = contactsFetcher.fetchCollection();
        contactsCollection.toElement(response);

        Element el = response.addUniqueElement("existing");
        contactsFetcher.mExistingCollection.toElement(el);

        return response;
    }

}
