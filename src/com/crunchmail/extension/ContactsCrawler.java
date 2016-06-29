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
import com.zimbra.cs.index.SortBy;
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
import com.zimbra.cs.mailbox.Contact;
import com.zimbra.cs.mailbox.ContactGroup;
import com.zimbra.cs.mailbox.OperationContext;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.UserSettings;

/**
 *
 */
public abstract class ContactsCrawler {

    private static class NoMailException extends Exception {}
    private static class FolderNodeIgnoredException extends Exception {}
    private static class EmptyGroupException extends Exception {}

    private final Map<String, String> mGalAttrMap = new HashMap<String, String>() {
        {
            put("firstName", "givenName");
            put("lastName", "sn");
            // TODO complete me !
        };
    };

    /**
     *
     */
    public class Collection {
        private ContactsCollection mContacts = new ContactsCollection();
        private GroupsCollection mGroups = new GroupsCollection();

        public void merge(Collection other) {
            mContacts.merge(other.mContacts);
            mGroups.merge(other.mGroups);
        }

        public void toElement(Element el) {
            if (mContacts.isEmpty()) {
                // add empty element so client doesn't have to test
                el.addNonUniqueElement("contacts");
            } else {
                for (ContactObject contact : mContacts) {
                    Element c = el.addNonUniqueElement("contacts");
                    contact.toElement(c);
                }
            }

            if (mGroups.isEmpty()) {
                // add empty element so client doesn't have to test
                el.addNonUniqueElement("groups");
            } else {
                for (GroupObject group : mGroups) {
                    Element g = el.addNonUniqueElement("groups");
                    group.toElement(g);
                }
            }
        }
    }

    /**
     *
     */
    public class Tree {
        private String mName;
        private boolean mHide = false;
        private boolean mIsShare;
        private String mColor;
        private ContactsCollection mContacts = new ContactsCollection();
        private GroupsCollection mGroups = new GroupsCollection();
        private List<Tree> mSubfolders = new ArrayList<Tree>();

        public void merge(Tree other) {
            merge(other, other.mName, other.mColor);
        }

        public void merge(Tree other, String nodeName, String color) {
            mName = nodeName;
            mColor = color;
            mIsShare = other.mIsShare;
            mContacts.merge(other.mContacts);
            mGroups.merge(other.mGroups);

            for (Tree subfolder : other.mSubfolders) {
                Tree newTree = new Tree();
                newTree.merge(subfolder);
                mSubfolders.add(newTree);
            }
        }

        public void toElement(Element el) {
            Element t = el.addUniqueElement("tree");
            makeElement(t);
        }

        private void makeElement(Element el) {
            if (mHide) {
                crawlTree(el);
            } else {
                Element f = el.addUniqueElement(mName);

                Element a = f.addUniqueElement("_attrs");
                a.addAttribute("isShare", mIsShare);
                a.addAttribute("color", mColor);

                if (mContacts.isEmpty()) {
                    // add empty element so client doesn't have to test
                    f.addNonUniqueElement("contacts");
                } else {
                    for (ContactObject contact : mContacts) {
                        Element c = f.addNonUniqueElement("contacts");
                        contact.toElement(c);
                    }
                }

                if (mGroups.isEmpty()) {
                    // add empty element so client doesn't have to test
                    f.addNonUniqueElement("groups");
                } else {
                    for (GroupObject group : mGroups) {
                        Element g = f.addNonUniqueElement("groups");
                        group.toElement(g);
                    }
                }

                Element s = f.addUniqueElement("_subfolders");
                crawlTree(s);
            }
        }

        private void crawlTree(Element el) {
            for (Tree subfolder : mSubfolders) {
                subfolder.makeElement(el);
            }
        }
    }

    /**
     *
     */
    class ContactsCollection implements Iterable<ContactObject> {
        private List<ContactObject> mCollection = new ArrayList<ContactObject>();

        void add(ContactObject contact) {
            mCollection.add(contact);
        }

        void merge(ContactsCollection other) {
            mCollection.addAll(other.mCollection);
        }

        boolean isEmpty() {
            return mCollection.isEmpty();
        }

        @Override
        public Iterator<ContactObject> iterator() {
            return mCollection.iterator();
        }
    }

    /**
     *
     */
    class ContactObject {
        private String mId;
        private String mEmail;
        private String mName;
        private Map<String, String> mProperties = new HashMap<String, String>();
        private List<String> mTags = new ArrayList<String>();
        private String mSourceType = "zimbra";
        private String mSourceRef;
        private boolean mGroupMember = false;

        public ContactObject(Contact contact) throws ServiceException, NoMailException {
            this(contact, null);
        }

        public ContactObject(Object object, String ref) throws ServiceException, NoMailException {
            String[] includeFields = mSettings.getArray(UserSettings.CONTACTS_ATTRS, ",", mIncludeFieldsDefault);
            // If we're being called by another server, use their defaults instead
            if (mForceConsiderShared) includeFields = mIncludeFieldsDefault;

            String debug_prefix = "";
            if (ref != null) {
                debug_prefix = "Group member - ";
                mGroupMember = true;
            }

            if (object instanceof String) {

                // Inline group member
                mLogger.debug(debug_prefix + "Making contact object with String instance: " + object);
                mEmail = (String) object;

                // set empty properties since we don't have any
                for (String field : includeFields) {
                    mProperties.put(field, "");
                }

                mSourceRef = ref;

            } else if (object instanceof Contact) {

                // Normal contact (local or shared) or contact group member
                Contact contact = (Contact) object;
                Map<String, String> contactFields = contact.getFields();

                if (contactFields.containsKey("email")) {
                    mLogger.debug(debug_prefix + "Making contact object with Contact instance: " + contactFields.get("email"));

                    mEmail = contactFields.get("email");

                    for (String field : includeFields) {
                        String value = contactFields.get(field);
                        if (value != null) {
                            mProperties.put(field, value);
                        } else {
                            mProperties.put(field, "");
                        }
                    }

                    // if building a normal contact, we need these attributes
                    if (!mGroupMember) {
                        mName = contact.getFileAsString();
                        mTags = Arrays.asList(contact.getTags());
                        // Add an ID attribute for use by frontend.
                        // This is only usefull here, all other object types are group members
                        mId = contact.getAccount().getId() + ":" + contact.getId();
                        // For regular contacts we construct the sourceRef ourselves
                        mSourceRef = "contact:" + mId;
                    } else {
                        mSourceRef = ref;
                    }

                } else {
                    // Contacts without an email address are useless to us
                    mLogger.debug(debug_prefix + "Contact instance has no email, ignoring");
                    throw new NoMailException();
                }

            } else if (object instanceof GalContact) {

                // GAL reference
                /**
                 *  Note: references to server accounts in groups actually appear as Contact objects.
                 *  But let's still support this, who knows...
                 */
                GalContact galContact = (GalContact) object;
                Map<String, Object> contactFields = galContact.getAttrs();
                Object email = contactFields.get("email");

                // Contacts without an email address are useless to us
                if (email instanceof String) {
                    mLogger.debug(debug_prefix + "Making contact object with GALContact instance: " + email);
                    mEmail = (String) email;
                } else if (email instanceof String[]) {
                    // Multiple email addresses (alias), so get the main one
                    email = contactFields.get("zimbraMailDeliveryAddress");
                    mLogger.debug(debug_prefix + "Making contact object with GALContact instance: " + email);
                    mEmail = (String) email;
                } else {
                    // Contacts without an email address are useless to us
                    mLogger.debug(debug_prefix + "GAL contact instance has no email, ignoring");
                    throw new NoMailException();
                }

                for (String field : includeFields) {
                    if (mGalAttrMap.containsKey(field)) {
                        String galField = mGalAttrMap.get(field);
                        String value = (String) contactFields.get(galField);
                        if (value != null) {
                            mProperties.put(field, value);
                        } else {
                            mProperties.put(field, "");
                        }
                    } else {
                        mProperties.put(field, "");
                    }
                }

                mSourceRef = ref;

            } else if (object instanceof Element) {

                // Not sure what this is, treat it as an inline member (email only) for now.
                Element elem = (Element) object;
                for (Element eAttr : elem.listElements(MailConstants.E_ATTRIBUTE)) {
                    String field = eAttr.getAttribute(MailConstants.A_ATTRIBUTE_NAME, null);
                    // Try to find email
                    if (field == "email") {
                        String content = eAttr.getText();
                        if (!Strings.isNullOrEmpty(content)) {
                            mEmail = content;
                        }
                    }
                }
                // Test if an email was found
                if (mEmail == null) {
                    // Contacts without an email address are useless to us
                    mLogger.debug(debug_prefix + "Element instance has no email, ignoring");
                    throw new NoMailException();
                }

                // TODO: figure out how to better handle properties
                // for now we set empty ones
                for (String field : includeFields) {
                    mProperties.put(field, "");
                }

                mSourceRef = ref;
            }
        }

        public void toElement(Element c) {
            c.addAttribute("email", mEmail);
            if (!mGroupMember) {
                c.addAttribute("name", mName);
                c.addAttribute("id", mId);
            }

            Element p = c.addUniqueElement("properties");
            for (Map.Entry<String, String> property : mProperties.entrySet()) {
                p.addAttribute(property.getKey(), property.getValue());
            }

            for (String tag : mTags) {
                Element t = c.addNonUniqueElement("tags");
                t.addAttribute("name", tag);
            }

            c.addAttribute("sourceType", mSourceType);
            c.addAttribute("sourceRef", mSourceRef);
        }
    }

    /**
     *
     */
    class GroupsCollection implements Iterable<GroupObject> {
        private List<GroupObject> mCollection = new ArrayList<GroupObject>();

        void add(GroupObject group) {
            mCollection.add(group);
        }

        void merge(GroupsCollection other) {
            mCollection.addAll(other.mCollection);
        }

        boolean isEmpty() {
            return mCollection.isEmpty();
        }

        @Override
        public Iterator<GroupObject> iterator() {
            return mCollection.iterator();
        }
    }

    /**
     *
     */
    class GroupObject {
        private String mId;
        private String mName;
        private List<ContactObject> mMembers = new ArrayList<ContactObject>();
        private List<String> mTags = new ArrayList<String>();
        private List<Map<String, String>> mFailedDeref = new ArrayList<Map<String, String>>();

        public GroupObject(Contact group, Mailbox mbox) throws ServiceException, EmptyGroupException {
            String encodedGroupMembers = group.get(ContactConstants.A_groupMember);

            try {
                ContactGroup contactGroup = ContactGroup.init(encodedGroupMembers);

                contactGroup.derefAllMembers(mbox, mOctxt);
                List<ContactGroup.Member> members = contactGroup.getDerefedMembers();

                // A group without members is useless
                if (members.isEmpty()) {
                    mLogger.debug("Ignoring group "+ group.getFileAsString() +". It has no members.");
                    throw new EmptyGroupException();
                }

                mLogger.debug("Contact group: " + group.getFileAsString());

                // Add an ID attribute for use by frontend
                mId = group.getAccount().getId() + ':' + group.getId();
                mName = group.getFileAsString();
                mTags = Arrays.asList(group.getTags());

                for (ContactGroup.Member member : members) {
                    Object memberObj = member.getDerefedObj();
                    // build contact entry
                    if (memberObj != null) {

                        String ref = "group:" + mId;
                        try {
                            ContactObject contactObj = new ContactObject(memberObj, ref);
                            mMembers.add(contactObj);
                        } catch (NoMailException e) {}

                    } else {
                        // Record failed member deref
                        Map<String, String> failed = new HashMap<String, String>();
                        failed.put("type", member.getType().toString());
                        failed.put("value", member.getValue());
                        mFailedDeref.add(failed);
                    }
                }

            } catch (ServiceException e) {
                mLogger.warn("Unable to decode contact group", e);
            }
        }

        // private List<String> mFailedDeref = new ArrayList<String>();

        public void toElement(Element g) {
            g.addAttribute("name", mName);
            g.addAttribute("id", mId);
            for (ContactObject contact : mMembers) {
                Element m = g.addNonUniqueElement("members");
                contact.toElement(m);
            }
            for (String tag : mTags) {
                Element t = g.addNonUniqueElement("tags");
                t.addAttribute("name", tag);
            }
            for (Map<String, String> failedDeref : mFailedDeref) {
                Element f = g.addNonUniqueElement("failed");
                f.addAttribute("type", failedDeref.get("type"));
                f.addAttribute("value", failedDeref.get("value"));
            }
        }
    }

    Logger mLogger;
    Mailbox mMbox;
    OperationContext mOctxt;
    UserSettings mSettings;
    String[] mIncludeFieldsDefault;
    boolean mForceConsiderShared;

    Collection mCollection;
    Tree mTree;

    private String mAuthToken;
    private boolean mAsTree = false;

    /**
     * [ContactsCrawler description]
     * @param   [description]
     * @param   [description]
     * @param   [description]
     * @param   [description]
     * @param   [description]
     * @return  ContactsCrawler instance
     */
    protected ContactsCrawler(Mailbox mbox, Account account, boolean debug, String[] includeFieldsDefault, boolean forceConsiderShared) throws ServiceException {
        mLogger = new Logger(debug);
        mMbox = mbox;
        mOctxt = new OperationContext(mbox);
        mSettings = new UserSettings(account);
        mIncludeFieldsDefault = includeFieldsDefault;
        mForceConsiderShared = forceConsiderShared;

        mAuthToken = getAuthToken();
    }

    /**
     * [crawl description]
     * @param  [description]
     * @param  [description]
     */
    protected void crawl(ItemId root, boolean asTree) throws ServiceException {
        FolderNode tree = mMbox.getFolderTree(mOctxt, root, true);
        mAsTree = asTree;
        if (asTree) {
            try {
                mTree = handleFolderNode(tree, mMbox);
            } catch (FolderNodeIgnoredException e) {
                // FolderNodeIgnoredException can't really reach here
                // but we have to catch it. Log a warning if it happens.
                mLogger.warn("We got a FolderNode ignored exception on ROOT. Something is wrong !", e);
            }
        } else {
            mCollection = new Collection();
            try {
                handleFolderNode(tree, mMbox);
            } catch (FolderNodeIgnoredException e) {
                // FolderNodeIgnoredException can't really reach here
                // but we have to catch it. Log a warning if it happens.
                mLogger.warn("We got a FolderNode ignored exception on ROOT. Something is wrong !", e);
            }
        }
    }

    private String getAuthToken() {
        try {
            AuthToken token = mOctxt.getAuthToken();
            return token.getEncoded();
        } catch (ServiceException|AuthTokenException e) {
            mLogger.warn("Unable to get encoded auth token: " + e);
        }
        return null;
    }

    private boolean shouldConsider(Folder folder) {
        if (folder.getId() == Mailbox.ID_FOLDER_AUTO_CONTACTS) {
            // We ignore "Emailed Contacts"
            return false;
        }
        // We consider to types:
        //
        //   - Actual contacts folders (local or mountpoints)
        //   - Folders with Unknown type that are mountpoints (Root of entire accounts shares)
        return (
            folder.getDefaultView() == MailItem.Type.CONTACT ||
            (folder.getDefaultView() == MailItem.Type.UNKNOWN && folder.getType() == MailItem.Type.MOUNTPOINT)
        );
    }

    private Tree handleFolderNode(FolderNode node, Mailbox mbox) throws ServiceException, FolderNodeIgnoredException {
        Tree treeNode = null;
        if (mAsTree) treeNode = new Tree();

        if (node.mFolder.getId() == Mailbox.ID_FOLDER_USER_ROOT) {

            // Root node has no content or type that we can handle
            // simply register it as hidden and continue crawling on level-1 folders
            handleFolderContent(node, node.mName, mbox, treeNode, true);
            if (mAsTree) treeNode.mHide = true;

        } else if (shouldConsider(node.mFolder)) {
            mLogger.debug("Considering folder: " + node.mName + ". Type: " + node.mFolder.getType());
            // Only consider mountpoints if we're in our own account.
            // This means:
            //
            //   - current mbox object is the same as the mbox object passed to constructor
            //   - AND forceConsiderShared is false, meaning we're not being called by another server
            if (node.mFolder.getType() == MailItem.Type.MOUNTPOINT && (mbox == mMbox && !mForceConsiderShared)) {

                handleMountpoint(node, mbox, treeNode);

            } else if (node.mFolder.getType() != MailItem.Type.MOUNTPOINT) {

                mLogger.debug("Address book: " + node.mName);
                handleFolderContent(node, node.mName, mbox, treeNode);
            } else {
                mLogger.debug("Ignoring recursive mountpoint " + node.mName);
                throw new FolderNodeIgnoredException();
            }
        } else {
            throw new FolderNodeIgnoredException();
        }

        return treeNode;
    }

    private void handleMountpoint(FolderNode node, Mailbox mbox, Tree treeNode) throws ServiceException, FolderNodeIgnoredException {
        // Crawl mountpoint
        mLogger.debug("Shared address book: " + node.mName);
        if(mSettings.getBool(UserSettings.INCLUDE_SHARED)) {
            Mountpoint mp = mbox.getMountpointById(mOctxt, node.mFolder.getId());
            // Get all the elements necessary
            String ownerId = mp.getOwnerId();
            ItemId itemId = mp.getTarget();

            // Check if account is on the same server, otherwise we can't do anything else here
            Account ownerAccount = Provisioning.getInstance().getAccount(ownerId);
            String currentServer = mbox.getAccount().getMailHost();
            String ownerServer = ownerAccount.getMailHost();

            if (currentServer.equals(ownerServer)) {
                mLogger.debug("Target account is on same server, crawling mountpoint");
                Mailbox rmbox = MailboxManager.getInstance().getMailboxByAccountId(ownerId, false);

                try {

                    // will throw an exception if current user does not have sufficient permissions on owner's object
                    // IMPORTANT: needs false as last argument for exception to be thrown
                    FolderNode sharedFolder = rmbox.getFolderTree(mOctxt, itemId, false);

                    // Two options here :
                    //
                    //   - Mountpoint is a classic share: treat it normally
                    //   - Mountpoint is the Root of a full-account share: register the node with the local name but ignore its content
                    boolean skipContent = false;
                    if (sharedFolder.mFolder.getId() == Mailbox.ID_FOLDER_USER_ROOT) {
                        skipContent = true;
                    }
                    handleFolderContent(sharedFolder, node.mName, rmbox, treeNode, skipContent);

                } catch (ServiceException e) {

                    if (e.getCode().equals(ServiceException.PERM_DENIED)) {
                        // if it is a permission denied, fail gracefully
                        mLogger.debug("Ignoring shared address book: " + mp.getPath() + ". Permission denied.");
                        throw new FolderNodeIgnoredException();
                    } else {
                        // re-raise
                        throw e;
                    }

                }
            } else {
                // Request the folder content from the other server
                mLogger.debug("Fetching content from remote server");
                String includeFields = mSettings.get(UserSettings.CONTACTS_ATTRS, Joiner.on(",").join(mIncludeFieldsDefault));
                String color = node.mFolder.getRgbColor().toString();

                handleRemoteFolderContent(ownerServer, ownerId, itemId.getId(), node.mName, includeFields, color, treeNode);
            }
        }
    }

    private void handleFolderContent(FolderNode node, String nodeName, Mailbox mbox, Tree treeNode) throws ServiceException {
        // This is the mose commonly used version.
        // skipContent is only passed when folder is the local root node or full-account root mountpoint
        handleFolderContent(node, nodeName, mbox, treeNode, false);
    }

    private void handleFolderContent(FolderNode node, String nodeName, Mailbox mbox, Tree treeNode, boolean skipContent) throws ServiceException {

        Folder f = node.mFolder;

        if (mAsTree) {
            treeNode.mName = nodeName;
            treeNode.mIsShare = mForceConsiderShared || mbox != mMbox;
            treeNode.mColor = f.getRgbColor().toString();
        }

        if (!skipContent) {
            // This will return contacts and contact groups
            List<Contact> contacts = mbox.getContactList(mOctxt, f.getId(), SortBy.NAME_ASC);

            for (Contact contact : contacts) {
                if (contact.isContactGroup()) {
                    try {
                        GroupObject groupObj = new GroupObject(contact, mbox);
                        if (mAsTree) {
                            treeNode.mGroups.add(groupObj);
                        } else {
                            mCollection.mGroups.add(groupObj);
                        }
                    } catch (EmptyGroupException e) {}
                } else {
                    try {
                        ContactObject contactObj = new ContactObject(contact);
                        if (mAsTree) {
                            treeNode.mContacts.add(contactObj);
                        } else {
                            mCollection.mContacts.add(contactObj);
                        }
                    } catch (NoMailException e) {}
                }
            }
        }

        // Recurse throw subtree
        for (FolderNode subnode : node.mSubfolders) {
            try {
                Tree child = handleFolderNode(subnode, mbox);
                if (mAsTree) {
                    treeNode.mSubfolders.add(child);
                }
            } catch (FolderNodeIgnoredException e) {}
        }
    }

    private void handleRemoteFolderContent(String serverName, String ownerId, int itemId, String nodeName, String includeFields, String color, Tree treeNode) throws ServiceException, FolderNodeIgnoredException {
        if (mAuthToken != null) {
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

                req.addHeader("Authorization", "TOKEN "+mAuthToken);
                req.addHeader("Content-Type", "application/json");

                HashMap<String, Object> data = new HashMap<String, Object>();
                data.put("account", ownerId);
                data.put("item", itemId);
                data.put("include_fields", includeFields);
                data.put("tree", mAsTree);

                Gson gson = new Gson();

                StringEntity params = new StringEntity(gson.toJson(data));
                req.setEntity(params);

                mLogger.debug("Getting remote folder " + itemId + " from account " + ownerId + " on server " + serverName);
                HttpResponse resp = httpClient.execute(req);
                String json = EntityUtils.toString(resp.getEntity(), "UTF-8");

                if (resp.getStatusLine().getStatusCode() == 200) {

                    if (mAsTree) {
                        Tree remoteTree = gson.fromJson(json, Tree.class);
                        treeNode.merge(remoteTree, nodeName, color);
                    } else {
                        Collection remoteCollection = gson.fromJson(json, Collection.class);
                        mCollection.merge(remoteCollection);
                    }

                } else {
                    Type type = new TypeToken<HashMap<String, String>>(){}.getType();
                    Map<String, String> ret = gson.fromJson(json, type);

    			    mLogger.warn("Request for remote folder returned an error: " + ret.get("error"));
                    throw new FolderNodeIgnoredException();
    		    }

            } catch (IOException e) {
                mLogger.warn("Error while making HTTP request for remote folder: " + e);
                throw new FolderNodeIgnoredException();
            }

        } else {
            mLogger.warn("Can't get remote folder, auth token is null (id: " + itemId + ", account: " + ownerId + ", server: " + serverName + ")");
            throw new FolderNodeIgnoredException();
        }
    }
}
