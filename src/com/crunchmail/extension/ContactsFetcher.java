package com.crunchmail.extension;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import com.google.common.base.Strings;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.GalContact;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Mailbox.FolderNode;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.cs.mailbox.Mountpoint;
import com.zimbra.cs.mailbox.Contact;
import com.zimbra.cs.mailbox.ContactGroup;
import com.zimbra.cs.mailbox.ContactGroup.Member;
import com.zimbra.cs.mailbox.OperationContext;
import com.zimbra.cs.index.SortBy;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.UserSettings;

/**
 *
 */
public class ContactsFetcher {

    /**
     * Internal Crawler class for actual data retrieval from mailbox
     */
    private static class Crawler {

        private Logger logger = new Logger();

        private Mailbox mbox;
        private OperationContext octxt;

        public List<Folder> addressBooks;
        public List<Mountpoint> sharedAddressBooks;
        public List<Contact> contacts;
        public List<HashMap<String, Object>> groups;

        public Crawler(Mailbox mbox) throws ServiceException {
            this.mbox = mbox;
            octxt = new OperationContext(mbox);
            addressBooks = new ArrayList<Folder>();
            sharedAddressBooks = new ArrayList<Mountpoint>();
            contacts = new ArrayList<Contact>();
            groups = new ArrayList<HashMap<String, Object>>();
        }

        private boolean isAddressBook(Folder folder) {
            if (folder.getId() == Mailbox.ID_FOLDER_AUTO_CONTACTS) {
                // We ignore "Emailed Contacts"
                return false;
            }
            return (folder.getDefaultView() == MailItem.Type.CONTACT);
        }

        private void crawlAddressBooks(boolean includeShared) throws ServiceException {
            // Crawl through local folders first
            for (MailItem item : mbox.getItemList(octxt, MailItem.Type.FOLDER, -1)) {
                if (isAddressBook((Folder) item)) {
                    try {
                        Mountpoint mp = (Mountpoint) item;
                    } catch (ClassCastException e) {
                        addressBooks.add((Folder) item);
                    }
                }
            }

            // Then crawl through shared folders
            if (includeShared) {
                for (MailItem item : mbox.getItemList(octxt, MailItem.Type.MOUNTPOINT, -1)) {
                    if (isAddressBook((Folder) item)) {
                        sharedAddressBooks.add((Mountpoint) item);
                    }
                }
            }
        }

        private void handleContact(Contact contact, Mailbox mbox, OperationContext octxt) throws ServiceException {
            if (contact.isContactGroup()) {
                String encodedGroupMembers = contact.get(ContactConstants.A_groupMember);
                try {
                    HashMap<String, Object> groupObj = new HashMap<String, Object>();
                    List<Object> groupMembers = new ArrayList<Object>();

                    ContactGroup group = ContactGroup.init(encodedGroupMembers);
                    group.derefAllMembers(mbox, octxt);
                    List<Member> members = group.getMembers(true);

                    for (Member member : members) {
                        groupMembers.add(member.getDerefedObj());
                    }

                    groupObj.put("group", contact);
                    groupObj.put("members", groupMembers);
                    groups.add(groupObj);
                } catch (ServiceException e) {
                    logger.warn("Unable to decode contact group", e);
                }
            } else {
                contacts.add(contact);
            }
        }

        public void crawlContacts(boolean includeShared) throws ServiceException {

            crawlAddressBooks(includeShared);

            logger.info("Crawling contacts (including shared: " + includeShared + ")");
            for (Folder f : addressBooks) {
                // This will return contacts and contact groups
                List<Contact> abContacts = mbox.getContactList(octxt, f.getId(), SortBy.NAME_ASC);
                for (Contact contact : abContacts) {
                    handleContact(contact, mbox, octxt);
                }
            }

            if (includeShared) {
                for (Mountpoint mp : sharedAddressBooks) {
                    // Get all the elements necessary
                    String oid = mp.getOwnerId();
                    ItemId iid = mp.getTarget();
                    Mailbox rmbox = MailboxManager.getInstance().getMailboxByAccountId(oid);

                    FolderNode sharedFolder;
                    try {
                        // will throw an exception if current user does not have sufficient permissions on owner's object
                        sharedFolder = rmbox.getFolderTree(octxt, iid, true);
                    } catch (ServiceException e) {
                        if (e.getCode().equals(ServiceException.PERM_DENIED)) {
                            // if it is a permission denied, fail gracefully
                            logger.info("Ignoring shared address book: " + mp.getPath() + ". Permission denied.");
                            continue;
                        } else {
                            // re-raise
                            throw e;
                        }
                    }

                    // This will return contacts and contact groups
                    List<Contact> abContacts = rmbox.getContactList(octxt, sharedFolder.mId, SortBy.NAME_ASC);
                    for (Contact contact : abContacts) {
                        handleContact(contact, rmbox, octxt);
                    }
                    // Crawl subtree
                    for (FolderNode node : sharedFolder.mSubfolders) {
                        abContacts = rmbox.getContactList(octxt, node.mId, SortBy.NAME_ASC);
                        for (Contact contact : abContacts) {
                            handleContact(contact, rmbox, octxt);
                        }
                    }
                }
            }
        }
    }

    /**
     * ContactsFetcher attributes and methods
     */
    private Logger logger = new Logger();
    private boolean asTree;
    private UserSettings settings;
    private Crawler crawler;

    public ContactsFetcher(Mailbox mbox, Account account) throws ServiceException {
        this(mbox, account, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, Boolean asTree)
        throws ServiceException {
            this.asTree = asTree;
            settings = new UserSettings(account);
            crawler = new Crawler(mbox);
    }

    public Map<String, List<HashMap<String, Object>>> fetch() throws ServiceException {

        HashMap<String, List<HashMap<String, Object>>> collection = new HashMap<String, List<HashMap<String, Object>>>();
        List<HashMap<String, Object>> contactsCollection = new ArrayList<HashMap<String, Object>>();
        List<HashMap<String, Object>> groupsCollection = new ArrayList<HashMap<String, Object>>();
        // Set<HashMap<String, Object>> contactsCollection = new HashSet<HashMap<String, Object>>();
        // Set<HashMap<String, Object>> groupsCollection = new HashSet<HashMap<String, Object>>();

        boolean includeShared = settings.getBool(UserSettings.INCLUDE_SHARED);

        String[] includeFieldsDefault = {ContactConstants.A_firstName, ContactConstants.A_lastName};
        String[] includeFields = settings.getArray(UserSettings.CONTACTS_ATTRS, ",", includeFieldsDefault);

        crawler.crawlContacts(includeShared);

        /**
         * Build a list of contact objects:
         *
         * {
         * 	 "email": "",
         *   "name": "",
         *   "properties": {
         * 	   "firstName": "",
         * 	   "lastName": "",
         * 	   ...
         * 	 },
         * 	 "tags": [],
         * 	 "sourceType": "zimbra",
         * 	 "sourceRef": "contact:AccountId:ContactId"
         * }
         *
         */
        for (Contact contact : crawler.contacts) {
            HashMap<String, Object> contactObj = new HashMap<String, Object>();
            Map<String, String> contactFields = contact.getFields();

            // Contacts without an email address are useless to us
            if (contactFields.containsKey("email")) {
                contactObj.put("email", contactFields.get("email"));
                contactObj.put("name", contact.getFileAsString());

                HashMap<String, String> properties = new HashMap<String, String>();
                for (String field : includeFields) {
                    String value = contactFields.get(field);
                    if (value != null) {
                        properties.put(field, value);
                    }
                }
                contactObj.put("properties", properties);
                contactObj.put("tags", contact.getTags());
                contactObj.put("sourceType", "zimbra");
                contactObj.put("sourceRef", "contact:" + contact.getAccount().getId() + ':' + contact.getId());
            }
            contactsCollection.add(contactObj);
        }

        /**
         * Build a list of contact groups objects:
         *
         * {
         *   "name": "",
         *   "members": [
         *     {
         * 	     "email": "",
         *       "properties": {
         * 	       "firstName": "",
         * 	       "lastName": "",
         * 	       ...
         * 	     },
         * 	     "sourceType": "zimbra",
         *       "sourceRef": "group:ID"
         *     },
         *     ...
         *   ],
         * 	 "tags": []
         * }
         *
         */
        for (HashMap<String, Object> group : crawler.groups) {
            HashMap<String, Object> groupObj = new HashMap<String, Object>();

            Contact groupDetails = (Contact) group.get("group");
            groupObj.put("name", groupDetails.getFileAsString());
            groupObj.put("tags", groupDetails.getTags());
            groupObj.put("members", new ArrayList<HashMap<String, Object>>());

            // Use a reference to add object in the loop below
            @SuppressWarnings("unchecked")
            List<HashMap<String, Object>> groupMembers = (List<HashMap<String, Object>>) groupObj.get("members");

            @SuppressWarnings("unchecked")
            List<Object> members = (List<Object>) group.get("members");
            for (Object member : members) {
                HashMap<String, Object> contactObj = new HashMap<String, Object>();
                // build contact entry
                if (member != null) {
                    if (member instanceof String) {

                        // Inline member
                        contactObj.put("email", member);
                        // contactObj.put("properties", new HashMap<String, String>());

                    } else if (member instanceof Contact) {

                        // Normal contact (local or shared, we don't care)
                        Contact contact = (Contact) member;
                        Map<String, String> contactFields = contact.getFields();
                        if (contactFields.containsKey("email")) {
                            contactObj.put("email", contactFields.get("email"));

                            HashMap<String, String> properties = new HashMap<String, String>();
                            for (String field : includeFields) {
                                String value = contactFields.get(field);
                                if (value != null) {
                                    properties.put(field, value);
                                }
                            }

                            contactObj.put("properties", properties);
                        } else {
                            // Contacts without an email address are useless to us
                            continue;
                        }

                    } else if (member instanceof GalContact) {

                        // GAL reference
                        /**
                         *  Note: references to server accounts in groups actually appear as Contact objects.
                         *  But let's still support this, who knows...
                         *
                         * TODO: map contact attrs to inetOrgPerson attrs (ex: lastName -> sn)
                         */
                        GalContact galContact = (GalContact) member;
                        Map<String, Object> contactFields = galContact.getAttrs();
                        Object email = contactFields.get("email");
                        if (email instanceof String) {
                            contactObj.put("email", (String) email);
                        } else if (email instanceof String[]) {
                            // Multiple email addresses (alias), so get the main one
                            email = contactFields.get("zimbraMailDeliveryAddress");
                            contactObj.put("email", (String) email);
                        } else {
                            // Contacts without an email address are useless to us
                            continue;
                        }

                        HashMap<String, String> properties = new HashMap<String, String>();
                        for (String field : includeFields) {
                            String value = (String) contactFields.get(field);
                            if (value != null) {
                                properties.put(field, value);
                            }
                        }
                        contactObj.put("properties", properties);

                    } else if (member instanceof Element) {

                        // Not sure what this is, treat it as an inline member (email only) for now.
                        Element elem = (Element) member;
                        for (Element eAttr : elem.listElements(MailConstants.E_ATTRIBUTE)) {
                            String field = eAttr.getAttribute(MailConstants.A_ATTRIBUTE_NAME, null);
                            if (field == "email") {
                                String content = eAttr.getText();
                                if (!Strings.isNullOrEmpty(content)) {
                                    contactObj.put("email", content);
                                    // contactObj.put("properties", new HashMap<String, String>());
                                } else {
                                    // Contacts without an email address are useless to us
                                    continue;
                                }
                            } else {
                                // Contacts without an email address are useless to us
                                continue;
                            }
                        }

                    }

                    contactObj.put("sourceType", "zimbra");
                    contactObj.put("sourceRef", "group:" + groupDetails.getAccount().getId() + ':' + groupDetails.getId());

                    groupMembers.add(contactObj);
                }
            }
            groupsCollection.add(groupObj);
        }

        collection.put("contacts", contactsCollection);
        collection.put("groups", groupsCollection);
        return collection;
    }

}
