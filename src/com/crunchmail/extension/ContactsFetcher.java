package com.crunchmail.extension;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.lang.reflect.Type;
import java.io.IOException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import com.google.common.base.Strings;
import com.google.common.base.Joiner;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.account.ZAttrProvisioning;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthTokenException;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.GalContact;
import com.zimbra.cs.account.Server;
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

    private Logger logger;
    private Mailbox mbox;
    private OperationContext octxt;
    private UserSettings settings;
    private String[] includeFieldsDefault;
    private boolean forceConsiderShared;

    private List<HashMap<String, Object>> contactsCollection;
    private List<HashMap<String, Object>> groupsCollection;
    private List<HashMap<String, String>> remoteCollection;
    HashMap<String, Object> addressBookTree;

    public ContactsFetcher(Mailbox mbox, Account account) throws ServiceException {
        this(mbox, account, false, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug) throws ServiceException {
        this(mbox, account, debug, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug, String[] includeFieldsDefault) throws ServiceException {
        this(mbox, account, debug, new String[] {ContactConstants.A_firstName, ContactConstants.A_lastName}, false);
    }

    public ContactsFetcher(Mailbox mbox, Account account, boolean debug, String[] includeFieldsDefault, boolean forceConsiderShared) throws ServiceException {
        logger = new Logger(debug);
        this.mbox = mbox;
        octxt = new OperationContext(mbox);
        settings = new UserSettings(account);
        this.includeFieldsDefault = includeFieldsDefault;
        this.forceConsiderShared = forceConsiderShared;

        contactsCollection = new ArrayList<HashMap<String, Object>>();
        groupsCollection = new ArrayList<HashMap<String, Object>>();
        remoteCollection = new ArrayList<HashMap<String, String>>();
        addressBookTree = new HashMap<String, Object>();
    }

    private HashMap<String, Object> makeContactObj(Contact contact) throws ServiceException {
        String ref = "contact:" + contact.getAccount().getId() + ':' + contact.getId();
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

        String[] includeFields = settings.getArray(UserSettings.CONTACTS_ATTRS, ",", includeFieldsDefault);

        String debug_prefix = "";
        if (asGroupMember) {
            debug_prefix = "Group member - ";
        }

        if (object instanceof String) {

            // Inline group member
            logger.debug(debug_prefix + "Making contact object with String instance: " + object);
            contactObj.put("email", object);

        } else if (object instanceof Contact) {

            // Normal contact (local or shared) or contact group member
            Contact contact = (Contact) object;
            Map<String, String> contactFields = contact.getFields();

            if (contactFields.containsKey("email")) {
                logger.debug(debug_prefix + "Making contact object with Contact instance: " + contactFields.get("email"));

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
                    contactObj.put("tags", new ArrayList<String>(Arrays.asList(contact.getTags())));
                    // Add an ID attribute for use by frontend
                    String id = contact.getAccount().getId() + ":" + contact.getId();
                    contactObj.put("id", id);
                }

            } else {
                // Contacts without an email address are useless to us
                logger.debug(debug_prefix + "Contact instance has no email, ignoring");
                return null;
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
            // Test if an email was found
            if (contactObj.get("email") == null) {
                logger.debug(debug_prefix + "Element instance has no email, ignoring");
                return null;
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
    private HashMap<String, Object> makeGroupObj(Contact group, Mailbox mbx) throws ServiceException {
        HashMap<String, Object> groupObj = new HashMap<String, Object>();

        String encodedGroupMembers = group.get(ContactConstants.A_groupMember);

        try {
            List<HashMap<String, Object>> groupMembers = new ArrayList<HashMap<String, Object>>();

            ContactGroup contactGroup = ContactGroup.init(encodedGroupMembers);

            contactGroup.derefAllMembers(mbx, octxt);
            List<Member> members = contactGroup.getDerefedMembers();

            // A group without members is useless
            if (members.isEmpty()) {
                logger.debug("Ignoring group "+ group.getFileAsString() +". It has no members.");
                return null;
            }

            logger.debug("Contact group: " + group.getFileAsString());

            // Add an ID attribute for use by frontend
            String id = group.getAccount().getId() + ':' + group.getId();
            groupObj.put("id", id);

            for (Member member : members) {
                Object memberObj = member.getDerefedObj();
                // build contact entry
                if (memberObj != null) {
                    String ref = "group:" + id;
                    HashMap<String, Object> contactObj = makeContactObj(memberObj, ref, true);
                    if (contactObj != null) {
                        groupMembers.add(contactObj);
                    }
                }
            }

            groupObj.put("members", groupMembers);
            groupObj.put("name", group.getFileAsString());
            groupObj.put("tags", new ArrayList<String>(Arrays.asList(group.getTags())));

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
            attrs.put("isShare",  forceConsiderShared || mbx != mbox);
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

                HashMap<String, Object> groupObj = makeGroupObj(contact, mbx);
                if (groupObj != null) {
                    groupsCollection.add(groupObj);
                    int index = groupsCollection.indexOf(groupObj);

                    @SuppressWarnings("unchecked")
                    List<Integer> groups_index = (List<Integer>) treeEntry.get("groups_index");
                    groups_index.add(index);
                }
            } else {

                HashMap<String, Object> contactObj = makeContactObj(contact);
                if (contactObj != null) {
                    contactsCollection.add(contactObj);
                    int index = contactsCollection.indexOf(contactObj);

                    @SuppressWarnings("unchecked")
                    List<Integer> contacts_index = (List<Integer>) treeEntry.get("contacts_index");
                    contacts_index.add(index);
                }

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
                if (node.mFolder.getType() == MailItem.Type.MOUNTPOINT) {
                    // this is a mountpoint
                    logger.debug("Shared address book: " + node.mName);
                    if(settings.getBool(UserSettings.INCLUDE_SHARED)) {
                        Mountpoint mp = mbx.getMountpointById(octxt, node.mFolder.getId());
                        // Get all the elements necessary
                        String oid = mp.getOwnerId();
                        ItemId iid = mp.getTarget();

                        // Check if account is on the same server, otherwise we can't do anything else here
                        Account oacc = Provisioning.getInstance().getAccount(oid);
                        String currentServer = mbx.getAccount().getMailHost();
                        String ownerServer = oacc.getMailHost();

                        if (currentServer.equals(ownerServer)) {
                            logger.debug("Target account is on same server, crawling mountpoint");
                            Mailbox rmbox = MailboxManager.getInstance().getMailboxByAccountId(oid, false);

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
                                    logger.debug("Ignoring shared address book: " + mp.getPath() + ". Permission denied.");
                                } else {
                                    // re-raise
                                    throw e;
                                }
                            }
                        } else {
                            logger.debug("Marking mountpoint for future fetch, target account is on a different server");

                            HashMap<String, String> remote = new HashMap<String, String>();
                            remote.put("account", oid);
                            remote.put("item", Integer.toString(iid.getId()));
                            remote.put("include_fields", settings.get(UserSettings.CONTACTS_ATTRS, Joiner.on(",").join(includeFieldsDefault)));
                            remote.put("server", ownerServer);

                            remoteCollection.add(remote);
                            treeNode.put(node.mName, remote);
                        }
                    }
                } else {
                    logger.debug("Address book: " + node.mName);
                    HashMap<String, Object> subtree = handleFolderContent(node.mFolder, mbx, treeNode);

                    // Keep crawling local subtree
                    for (FolderNode subnode : node.mSubfolders) {
                        handleFolderNode(subnode, mbx, subtree);
                    }
                }
            } else {
                // This is only used if node is ROOT
                // skip it and launch the crawl on level-1 folders
                for (FolderNode subnode : node.mSubfolders) {
                    handleFolderNode(subnode, mbx, treeNode);
                }
            }
        }
    }

    private void crawlContacts(ItemId root) throws ServiceException {
        FolderNode tree = mbox.getFolderTree(octxt, root, true);
        handleFolderNode(tree, mbox, addressBookTree);
    }

    private String getAuthToken() {
        try {
            AuthToken token = octxt.getAuthToken();
            return token.getEncoded();
        } catch (ServiceException|AuthTokenException e) {
            logger.warn("Unable to get encoded auth token: " + e);
        }
        return null;
    }

    private String getRemoteFolder(String serverName, String token, String data) {
        try {
            DefaultHttpClient httpClient = new DefaultHttpClient();

            Server server = Provisioning.getInstance().getServerByName(serverName);
            ZAttrProvisioning.MailMode mode = server.getMailMode();
            String url = "";
            if (mode.isHttp()) {
                url += "http://"+serverName+":"+server.getMailPortAsString();
            } else {
                url += "https://"+serverName+":"+server.getMailSSLPortAsString();
            }
            url += "/service/extension/crunchmail/getremotefolder";

            HttpPost req = new HttpPost(url);

            req.addHeader("Authorization", "TOKEN "+token);
            req.addHeader("Content-Type", "application/json");

            StringEntity params = new StringEntity(data);
            req.setEntity(params);

            HttpResponse resp = httpClient.execute(req);
            String json = EntityUtils.toString(resp.getEntity(), "UTF-8");

            if (resp.getStatusLine().getStatusCode() != 200) {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> ret = gson.fromJson(json, type);

			    throw new RuntimeException("Request for remote folder returned an error: " + ret.get("error"));
		    }

            return json;

        } catch (ServiceException|IOException e) {
            throw new RuntimeException("Error while making HTTP request for remote folder: " + e);
        }

    }

    public Map<String, List<HashMap<String, Object>>> fetchCollection() throws ServiceException {
        ItemId root = new ItemId(mbox.getAccount().getId(), Mailbox.ID_FOLDER_USER_ROOT);
        return fetchCollection(root);
    }

    public Map<String, List<HashMap<String, Object>>> fetchCollection(ItemId root) throws ServiceException {
        Map<String, List<HashMap<String, Object>>> collection = new HashMap<String, List<HashMap<String, Object>>>();

        crawlContacts(root);

        String token = getAuthToken();
        if (token != null) {
            for (HashMap<String, String> remote : remoteCollection) {
                String server = (String) remote.get("server");
                remote.remove("server");
                remote.put("tree", "false");

                Gson gson = new Gson();
                try {

                    String resp = getRemoteFolder(server, token, gson.toJson(remote));
                    Type type = new TypeToken<Map<String, List<HashMap<String, Object>>>>(){}.getType();
                    Map<String, List<HashMap<String, Object>>> remoteFolderCollection = gson.fromJson(resp, type);
                    // Merge in our collections
                    contactsCollection.addAll(remoteFolderCollection.get("contacts"));
                    groupsCollection.addAll(remoteFolderCollection.get("groups"));

                } catch (RuntimeException e) {
                    // HTTP request failed. Log error, set an empty object and move on
                    logger.warn("Could not get remote folder collection. Error: "+ e.getMessage());
                }
            }
        }
        collection.put("contacts", contactsCollection);
        collection.put("groups", groupsCollection);
        return collection;
    }

    private void getRemoteTreeOrRecurse(Map.Entry<String, Object> entry) {
        @SuppressWarnings("unchecked")
        HashMap<String, Object> value = (HashMap<String, Object>) entry.getValue();
        if (value.get("server") != null) {
            logger.debug("Value: " + value);
            // HTTP request + replace entry value
            String token = getAuthToken();
            if (token != null) {
                String server = (String) value.get("server");
                value.remove("server");

                value.put("tree", "true");

                Gson gson = new Gson();
                try {

                    String resp = getRemoteFolder(server, token, gson.toJson(value));
                    Type type = new TypeToken<HashMap<String, Object>>(){}.getType();
                    HashMap<String, Object> remoteTree = gson.fromJson(resp, type);
                    // Replace entry value with result value
                    // This way we keep the mountpoint name instead of the remote folder name
                    entry.setValue(remoteTree.get(remoteTree.keySet().toArray()[0]));

                } catch (RuntimeException e) {

                    // HTTP request failed. Log error, set an empty object and move on
                    logger.warn("Could not get remote folder "+ entry.getKey() + ". Error: "+ e.getMessage());
                    value = new HashMap<String, Object>();
                    value.put("contacts", new ArrayList<HashMap<String, Object>>());
                    value.put("groups", new ArrayList<HashMap<String, Object>>());
                    value.put("_subfolders", new HashMap<String, Object>());
                    value.put("_attrs", new HashMap<String, Object>());
                    entry.setValue(value);

                }
            }
        } else {

            @SuppressWarnings("unchecked")
            List<Integer> contacts_index = (List<Integer>) value.get("contacts_index");
            List<HashMap<String, Object>> treeNodeContacts = new ArrayList<HashMap<String, Object>>();
            for (int index : contacts_index) {
                treeNodeContacts.add(contactsCollection.get(index));
            }
            value.put("contacts", treeNodeContacts);
            value.remove("contacts_index");

            @SuppressWarnings("unchecked")
            List<Integer> groups_index = (List<Integer>) value.get("groups_index");
            List<HashMap<String, Object>> treeNodeGroups = new ArrayList<HashMap<String, Object>>();
            for (int index : groups_index) {
                treeNodeGroups.add(groupsCollection.get(index));
            }
            value.put("groups", treeNodeGroups);
            value.remove("groups_index");

            @SuppressWarnings("unchecked")
            HashMap<String, Object> subfolders = (HashMap<String, Object>) value.get("_subfolders");
            for (Map.Entry<String, Object> subfolder : subfolders.entrySet()) {
                getRemoteTreeOrRecurse(subfolder);
            }

        }
    }

    public Map<String, Object> fetchTree() throws ServiceException {
        ItemId root = new ItemId(mbox.getAccount().getId(), Mailbox.ID_FOLDER_USER_ROOT);
        return fetchTree(root);
    }

    public Map<String, Object> fetchTree(ItemId root) throws ServiceException {

        crawlContacts(root);

        for (Map.Entry<String, Object> entry : addressBookTree.entrySet()) {
            getRemoteTreeOrRecurse(entry);
        }

        return addressBookTree;
    }

}
