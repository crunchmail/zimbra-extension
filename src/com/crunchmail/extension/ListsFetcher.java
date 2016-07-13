package com.crunchmail.extension;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import com.google.common.collect.Sets;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.common.account.Key;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Entry;
import com.zimbra.cs.account.Group;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.UserSettings;

/**
 *
 */
public class ListsFetcher {

    public class ListsCollection {
        private List<DistributionList> mCollection = new ArrayList<DistributionList>();

        void add(DistributionList list) {
            mCollection.add(list);
        }

        public void toElement(Element el) {
            if (mCollection.isEmpty()) {
                // add empty element so client doesn't have to test
                el.addNonUniqueElement("dls");
            } else {
                for (DistributionList list : mCollection) {
                    Element l = el.addNonUniqueElement("dls");
                    list.toElement(l);
                }
            }
        }
    }

    class DistributionList {
        private String mId;
        private String mName;
        private String mEmail;
        private Set<Member> mMembers = Sets.newHashSet();
        public boolean mAddToExisting = false;

        DistributionList(Group group) throws ServiceException {
            mId = group.getId();
            mName = group.getDisplayName();
            mEmail = group.getMail();

            // Use a set to keep track of nested groups and avoid loops
            // and add ourselves
            Set<String> nestedGroups = Sets.newHashSet();
            nestedGroups.add(group.getMail());

            String ref = "dl:" + mId + ":" + mId;

            if (mExisting.contains(ref)) {
                mAddToExisting = true;
                mExisting.remove(ref);
            }

            handleMembers(group, nestedGroups, ref);
        }

        private void handleMembers(Group group, Set<String> nestedGroups, String ref) throws ServiceException {
            mLogger.debug("LIST: " + group.getMail());

            Provisioning prov = Provisioning.getInstance();
            String[] members = group.getAllMembers();

            for (String groupMember : members) {

                mLogger.debug("MEMBER: " + groupMember);

                Account acct = prov.getAccount(groupMember);
                if (acct != null) {
                    Member member = new Member(groupMember, acct.getGivenName(), acct.getSn(), ref);
                    mMembers.add(member);
                } else {
                    // not an account, either a static member or another list
                    // first test for another list
                    Group nested = prov.getGroup(Key.DistributionListBy.name, groupMember);
                    if (nested != null && !nestedGroups.contains(groupMember)) {
                        nestedGroups.add(groupMember);
                        String nestedRef = "dl:" + mId + ":" + nested.getId();
                        if (mExisting.contains(nestedRef)) {
                            mAddToExisting = true;
                            mExisting.remove(nestedRef);
                        }
                        handleMembers(nested, nestedGroups, nestedRef);
                    } else if (nested != null) {
                        // already handled, ignore
                        mLogger.debug("Ignoring member in list " + group.getMail() + ". Nested group already handled: " + groupMember);
                    } else {
                        // static member
                        mLogger.debug("STATIC MEMBER: " + groupMember);
                        Member member = new Member(groupMember, null, null, ref);
                        mMembers.add(member);
                    }
                }
            }
        }

        void toElement(Element l) {
            l.addAttribute("id", mId);
            l.addAttribute("name", mName);
            l.addAttribute("email", mEmail);
            if (!mMembers.isEmpty()) {
                for (Member member : mMembers) {
                    Element m = l.addNonUniqueElement("members");
                    member.toElement(m);
                }
            }
        }
    }

    class Member {
        private String mEmail;
        private Map<String, String> mProperties = new HashMap<String, String>();
        private String mSourceType = "zimbra-dl";
        private String mSourceRef;

        Member(String member, String firstName, String lastName, String ref) {
            mEmail = member;
            mProperties.put("firstName", firstName != null ? firstName : "");
            mProperties.put("lastName", lastName != null ? lastName : "");
            mSourceRef = ref;
        }

        void toElement(Element m) {
            m.addAttribute("email", mEmail);
            Element p = m.addUniqueElement("properties");
            for (Map.Entry<String, String> property : mProperties.entrySet()) {
                p.addAttribute(property.getKey(), property.getValue());
            }
            m.addAttribute("sourceType", mSourceType);
            m.addAttribute("sourceRef", mSourceRef);
        }
    }

    Logger mLogger;
    UserSettings mSettings;
    Account mAccount;
    Set<String> mExisting;
    public ListsCollection mExistingCollection = new ListsCollection();

    public ListsFetcher(Account account, boolean debug, Set<String> existing) throws ServiceException {
        mAccount = account;
        mSettings = new UserSettings(account);
        mLogger = new Logger(debug);
        mExisting = existing;
    }

    private boolean shouldInclude(Group group) {
        boolean includeHidden = mSettings.getBool(UserSettings.DLIST_INCLUDE_HIDE_IN_GAL, true);
        if (group.hideInGal() && !includeHidden) {
            return false;
        }
        return true;
    }

    /**
     * [fetch description]
     * @return [description]
     */
    public ListsCollection fetch() throws ServiceException {
        ListsCollection collection = new ListsCollection();

        // Always get the lists the account is owner of
        mLogger.debug("Crawling distribution lists (ownerOf)");
        Set<Group> ownerOf = Group.GroupOwner.getOwnedGroups(mAccount);

        // Get the lists the account is member of if configured
        // and deduplicate lists
        if (mSettings.getBool(UserSettings.DLIST_MEMBER_OF)) {
            mLogger.debug("Crawling distribution lists (memberOf)");

            Provisioning prov = Provisioning.getInstance();
            HashMap<String, String> via = new HashMap<String, String>();

            List<Group> memberOf = prov.getGroups(mAccount, mSettings.getBool(UserSettings.DLIST_DIRECT_MEMBER_ONLY), via);

            Set<Entry> combined = Sets.newHashSet();
            Set<String> combinedIds = Sets.newHashSet();

            if (ownerOf != null) {
                for (Group group : ownerOf) {
                    String groupId = group.getId();
                    if (!combinedIds.contains(groupId)) {
                        combined.add(group);
                        combinedIds.add(groupId);
                    }
                }
            }
            if (memberOf != null) {
                for (Group group : memberOf) {
                    String groupId = group.getId();
                    if (!combinedIds.contains(groupId)) {
                        combined.add(group);
                        combinedIds.add(groupId);
                    }
                }
            }

            for (Entry entry : combined) {
                if (shouldInclude((Group) entry)) {
                    DistributionList list = new DistributionList((Group) entry);
                    collection.add(list);

                    if (list.mAddToExisting) mExistingCollection.add(list);
                }
            }
        } else {

            if (ownerOf != null) {
                for (Group group : ownerOf) {
                    if (shouldInclude(group)) {
                        DistributionList list = new DistributionList(group);
                        collection.add(list);

                        if (list.mAddToExisting) mExistingCollection.add(list);
                    }
                }
            }

        }

        return collection;
    }

}
