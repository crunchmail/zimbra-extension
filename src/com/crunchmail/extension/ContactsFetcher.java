package com.crunchmail.extension;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
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
import com.zimbra.cs.mailbox.Flag;
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
        private boolean includeShared;
        private boolean asTree;

        public List<Contact> contactsCollection;
        public List<HashMap<String, Object>> groupsCollection;
        HashMap<String, Object> addressBookTree;

        public Crawler(Mailbox mbox, boolean includeShared) throws ServiceException {
            this.mbox = mbox;
            octxt = new OperationContext(mbox);
            this.includeShared = includeShared;

            contactsCollection = new ArrayList<Contact>();
            groupsCollection = new ArrayList<HashMap<String, Object>>();
            addressBookTree = new HashMap<String, Object>();
        }

        private HashMap<String, Object> handleContactGroup(Contact contact, Mailbox mbx) throws ServiceException {
            HashMap<String, Object> groupObj = new HashMap<String, Object>();
            String encodedGroupMembers = contact.get(ContactConstants.A_groupMember);
            try {
                List<Object> groupMembers = new ArrayList<Object>();

                ContactGroup group = ContactGroup.init(encodedGroupMembers);
                group.derefAllMembers(mbx, octxt);
                List<Member> members = group.getMembers(true);

                for (Member member : members) {
                    groupMembers.add(member.getDerefedObj());
                }

                groupObj.put("group", contact);
                groupObj.put("members", groupMembers);
            } catch (ServiceException e) {
                logger.warn("Unable to decode contact group", e);
            }

            return groupObj;
        }

        private HashMap<String, Object> handleFolderContent(Folder f, Mailbox mbx, HashMap<String, Object> treeNode) throws ServiceException {
            // This is the mose commonly used version.
            // Parameter nodeName is only passed when folder is a mountpoint
            // to get the actual displayed name
            return handleFolderContent(f, mbx, treeNode, f.getName());
        }

        private HashMap<String, Object> handleFolderContent(Folder f, Mailbox mbx, HashMap<String, Object> treeNode, String nodeName) throws ServiceException {

            // Make tree entry if necessary
            @SuppressWarnings("unchecked")
            HashMap<String, Object> treeEntry = (HashMap<String, Object>) treeNode.get(f.getName());
            if (treeEntry == null) {
                treeEntry = new HashMap<String, Object>();

                HashMap<String, Object> attrs = new HashMap<String, Object>();
                attrs.put("isShare", mbx != mbox);
                attrs.put("color", f.getRgbColor().toString());
                treeEntry.put("_attrs", attrs);

                treeEntry.put("_subfolders", new HashMap<String, Object>());
                treeEntry.put("contacts_index", new ArrayList<Integer>());
                treeEntry.put("groups_index", new ArrayList<Integer>());

                treeNode.put(f.getName(), treeEntry);
            }

            // This will return contacts and contact groups
            List<Contact> contacts = mbx.getContactList(octxt, f.getId(), SortBy.NAME_ASC);

            for (Contact contact : contacts) {
                if (contact.isContactGroup()) {

                    HashMap<String, Object> groupObj = handleContactGroup(contact, mbx);
                    groupsCollection.add(groupObj);
                    int index = groupsCollection.indexOf(groupObj);

                    @SuppressWarnings("unchecked")
                    List<Integer> groups_index = (List<Integer>) treeEntry.get("groups_index");
                    groups_index.add(index);

                } else {

                    contactsCollection.add(contact);
                    int index = contactsCollection.indexOf(contact);

                    @SuppressWarnings("unchecked")
                    List<Integer> contacts_index = (List<Integer>) treeEntry.get("contacts_index");
                    contacts_index.add(index);

                }
            }

            @SuppressWarnings("unchecked")
            HashMap<String, Object> subfolders = (HashMap<String, Object>) treeEntry.get("_subfolders");

            return subfolders;
        }

        private boolean isAddressBook(Folder folder) {
            if (folder.getId() == Mailbox.ID_FOLDER_AUTO_CONTACTS) {
                // We ignore "Emailed Contacts"
                return false;
            }
            return (folder.getDefaultView() == MailItem.Type.CONTACT);
        }

        private void handleFolderNode(FolderNode node, Mailbox mbx, HashMap<String, Object> treeNode) throws ServiceException {
            if (isAddressBook(node.mFolder) || node.mFolder.getId() == Mailbox.ID_FOLDER_USER_ROOT) {
                if (node.mFolder.getId() != Mailbox.ID_FOLDER_USER_ROOT) {
                    logger.info(node.mName);
                    if (node.mFolder.getType() == MailItem.Type.MOUNTPOINT) {
                        // this is a mountpoint
                        if(includeShared) {
                            Mountpoint mp = mbx.getMountpointById(octxt, node.mFolder.getId());
                            // Get all the elements necessary
                            String oid = mp.getOwnerId();
                            ItemId iid = mp.getTarget();
                            Mailbox rmbox = MailboxManager.getInstance().getMailboxByAccountId(oid);

                            try {
                                // will throw an exception if current user does not have sufficient permissions on owner's object
                                FolderNode sharedFolder = rmbox.getFolderTree(octxt, iid, true);
                                HashMap<String, Object> subtree = handleFolderContent(sharedFolder.mFolder, rmbox, treeNode, mp.getName());

                                for (FolderNode subnode : sharedFolder.mSubfolders) {
                                    handleFolderNode(subnode, rmbox, subtree);
                                }
                            } catch (ServiceException e) {
                                if (e.getCode().equals(ServiceException.PERM_DENIED)) {
                                    // if it is a permission denied, fail gracefully
                                    logger.info("Ignoring shared address book: " + mp.getPath() + ". Permission denied.");
                                } else {
                                    // re-raise
                                    throw e;
                                }
                            }
                        }
                    } else {
                        HashMap<String, Object> subtree = handleFolderContent(node.mFolder, mbx, treeNode);

                        // Keep crawling local subtree
                        for (FolderNode subnode : node.mSubfolders) {
                            handleFolderNode(subnode, mbx, subtree);
                        }
                    }
                } else {
                    // This is only used on first run (node is ROOT)
                    // do nothing more than launch the crawl
                    for (FolderNode subnode : node.mSubfolders) {
                        handleFolderNode(subnode, mbx, treeNode);
                    }
                }
            }
        }

        public void crawlContacts() throws ServiceException {
            ItemId root = new ItemId(mbox.getAccount().getId(), Mailbox.ID_FOLDER_USER_ROOT);
            FolderNode tree = mbox.getFolderTree(octxt, root, true);
            handleFolderNode(tree, mbox, addressBookTree);
        }
    }

    /**
     * ContactsFetcher attributes and methods
     */
    private Logger logger = new Logger();
    private UserSettings settings;
    private Crawler crawler;

    public ContactsFetcher(Mailbox mbox, Account account) throws ServiceException {
        settings = new UserSettings(account);
        crawler = new Crawler(mbox, settings.getBool(UserSettings.INCLUDE_SHARED));
    }

    private HashMap<String, Object> makeContactObj(Contact contact) throws ServiceException {
        String ref = "contact:" + contact.getAccount().getId() + ':' + contact.getId();
        return makeContactObj(contact, ref, false);
    }

    private HashMap<String, Object> makeContactObj(Contact contact, String ref) throws ServiceException {
        return makeContactObj(contact, ref, false);
    }

    /**
     * Build a contact object
     * @param   object A String, Contact, GalContact or Element
     * @param   ref String to use as sourceRef
     * @param   asGroupMember boolean indicates the object is a group member
     * @return
     *
     * {
     * 	 "email": "",
     *   "name": "",                (if not asGroupMember)
     *   "properties": {
     * 	   "firstName": "",
     * 	   "lastName": "",
     * 	   ...
     * 	 },
     * 	 "tags": [],                (if not asGroupMember)
     * 	 "sourceType": "zimbra",
     * 	 "sourceRef": "ref"
     * }
     *
     */
    private HashMap<String, Object> makeContactObj(Object object, String ref, boolean asGroupMember) throws ServiceException {
        HashMap<String, Object> contactObj = new HashMap<String, Object>();

        String[] includeFieldsDefault = {ContactConstants.A_firstName, ContactConstants.A_lastName};
        String[] includeFields = settings.getArray(UserSettings.CONTACTS_ATTRS, ",", includeFieldsDefault);

        if (object instanceof String) {

            // Inline group member
            contactObj.put("email", object);

        } else if (object instanceof Contact) {

            // Normal contact (local or shared) or contact group member
            Contact contact = (Contact) object;
            Map<String, String> contactFields = contact.getFields();

            // Contacts without an email address are useless to us
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

                // if building a normal contact, we need these attributes
                if (!asGroupMember) {
                    contactObj.put("name", contact.getFileAsString());
                    contactObj.put("tags", contact.getTags());
                }

            }

        } else if (object instanceof GalContact) {

            // GAL reference
            /**
             *  Note: references to server accounts in groups actually appear as Contact objects.
             *  But let's still support this, who knows...
             *
             * TODO: map contact attrs to inetOrgPerson attrs (ex: lastName -> sn)
             */
            GalContact galContact = (GalContact) object;
            Map<String, Object> contactFields = galContact.getAttrs();
            Object email = contactFields.get("email");
            // Contacts without an email address are useless to us
            if (email instanceof String) {
                contactObj.put("email", (String) email);
            } else if (email instanceof String[]) {
                // Multiple email addresses (alias), so get the main one
                email = contactFields.get("zimbraMailDeliveryAddress");
                contactObj.put("email", (String) email);
            }

            HashMap<String, String> properties = new HashMap<String, String>();
            for (String field : includeFields) {
                String value = (String) contactFields.get(field);
                if (value != null) {
                    properties.put(field, value);
                }
            }
            contactObj.put("properties", properties);

        } else if (object instanceof Element) {

            // Not sure what this is, treat it as an inline member (email only) for now.
            Element elem = (Element) object;
            for (Element eAttr : elem.listElements(MailConstants.E_ATTRIBUTE)) {
                String field = eAttr.getAttribute(MailConstants.A_ATTRIBUTE_NAME, null);
                // Contacts without an email address are useless to us
                if (field == "email") {
                    String content = eAttr.getText();
                    if (!Strings.isNullOrEmpty(content)) {
                        contactObj.put("email", content);
                    }
                }
            }

        }

        contactObj.put("sourceType", "zimbra");
        contactObj.put("sourceRef", ref);

        return contactObj;
    }

    /**
     * Build a contact group object
     * @param   group HashMap<String, Object>
     * @return
     *
     * {
     *   "name": "",
     *   "members": [
     *     <Contact Object>,
     *     ...
     *   ],
     * 	 "tags": []
     * }
     *
     */
    private HashMap<String, Object> makeGroupObj(HashMap<String, Object> group) throws ServiceException {
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
            // build contact entry
            if (member != null) {
                String ref = "group:" + groupDetails.getAccount().getId() + ':' + groupDetails.getId();
                HashMap<String, Object> contactObj = makeContactObj(member, ref, true);
                groupMembers.add(contactObj);
            }
        }

        return groupObj;
    }

    public Map<String, Set<HashMap<String, Object>>> fetchCollection() throws ServiceException {
        HashMap<String, Set<HashMap<String, Object>>> collection = new HashMap<String, Set<HashMap<String, Object>>>();
        Set<HashMap<String, Object>> contactsCollection = new HashSet<HashMap<String, Object>>();
        Set<HashMap<String, Object>> groupsCollection = new HashSet<HashMap<String, Object>>();

        crawler.crawlContacts();

        for (Contact contact : crawler.contactsCollection) {
            HashMap<String, Object> contactObj = makeContactObj(contact);
            contactsCollection.add(contactObj);
        }

        for (HashMap<String, Object> group : crawler.groupsCollection) {
            HashMap<String, Object> groupObj = makeGroupObj(group);
            groupsCollection.add(groupObj);
        }

        collection.put("contacts", contactsCollection);
        collection.put("groups", groupsCollection);
        return collection;
    }

    private void recurseTree(HashMap<String, Object> entry) throws ServiceException {
        @SuppressWarnings("unchecked")
        List<Integer> contacts_index = (List<Integer>) entry.get("contacts_index");
        Set<HashMap<String, Object>> contactsCollection = new HashSet<HashMap<String, Object>>();
        for (int index : contacts_index) {
            Contact contact = crawler.contactsCollection.get(index);
            HashMap<String, Object> contactObj = makeContactObj(contact);
            contactsCollection.add(contactObj);
        }
        entry.put("contacts", contactsCollection);
        entry.remove("contacts_index");

        @SuppressWarnings("unchecked")
        List<Integer> groups_index = (List<Integer>) entry.get("groups_index");
        Set<HashMap<String, Object>> groupsCollection = new HashSet<HashMap<String, Object>>();
        for (int index : groups_index) {
            HashMap<String, Object> group = crawler.groupsCollection.get(index);
            HashMap<String, Object> groupObj = makeGroupObj(group);
            groupsCollection.add(groupObj);
        }
        entry.put("groups", groupsCollection);
        entry.remove("groups_index");

        @SuppressWarnings("unchecked")
        HashMap<String, Object> subfolders = (HashMap<String, Object>) entry.get("_subfolders");
        for (Map.Entry<String, Object> subfolder : subfolders.entrySet()) {
            @SuppressWarnings("unchecked")
            HashMap<String, Object> value = (HashMap<String, Object>) subfolder.getValue();
            recurseTree(value);
        }
    }

    public Map<String, Object> fetchTree() throws ServiceException {

        crawler.crawlContacts();

        logger.info("Tree"+crawler.addressBookTree);

        for (Map.Entry<String, Object> entry : crawler.addressBookTree.entrySet()) {
            @SuppressWarnings("unchecked")
            HashMap<String, Object> value = (HashMap<String, Object>) entry.getValue();
            recurseTree(value);
        }

        logger.info("Tree"+crawler.addressBookTree);

        return crawler.addressBookTree;
    }

}
