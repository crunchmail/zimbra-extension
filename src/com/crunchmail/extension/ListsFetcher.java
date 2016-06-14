package com.crunchmail.extension;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import com.google.common.collect.Sets;

import com.zimbra.common.service.ServiceException;
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

    private Logger logger;

    private UserSettings settings;
    private Account account;

    public ListsFetcher(Account account) throws ServiceException {
        this(account, false);
    }

    public ListsFetcher(Account account, boolean debug) throws ServiceException {
        this.account = account;
        settings = new UserSettings(account);
        logger = new Logger(debug);
    }

    /**
     * Handle parsing Group members recursively.
     * This version is usually called first when parsing group members.
     * The group's email address will be passed along to be used in
     * sourceRef for the nested members.
     * @param  group com.zimbra.cs.account.Group
     * @param  listMembers reference to a List<HashMap<String, Object>>
     * @param  nestedGroups reference to a Set<String>
     */
    private void handleGroupMembers(Group group, Set<HashMap<String, Object>> listMembers, Set<String> nestedGroups) throws ServiceException {
        handleGroupMembers(group, listMembers, nestedGroups, group.getMail());
    }

    /**
     * Handle parsing Group members recursively
     * @param  group com.zimbra.cs.account.Group
     * @param  listMembers reference to a List<HashMap<String, Object>>
     * @param  nestedGroups reference to a Set<String>
     * @param  listMail String
     */
    private void handleGroupMembers(Group group, Set<HashMap<String, Object>> listMembers, Set<String> nestedGroups, String listMail) throws ServiceException {

        Provisioning prov = Provisioning.getInstance();
        String[] members = group.getAllMembers();
        for (String member : members) {
            HashMap<String, Object> m = new HashMap<String, Object>();

            Account acct = prov.getAccount(member);
            if (acct == null) {
                // not an account, probably another list
                Group nested = prov.getGroup(Key.DistributionListBy.name, member);
                if (nested != null && !nestedGroups.contains(member)) {
                    nestedGroups.add(member);
                    handleGroupMembers(nested, listMembers, nestedGroups, listMail);
                    continue;
                } else {
                    // something else or already handled, ignore
                    logger.debug("Ignoring member in list "+group.getMail()+". Either a nested group already handled or an unknown type: "+member);
                    continue;
                }
            }
            m.put("email", member);

            HashMap<String, String> properties = new HashMap<String, String>();
            if (acct.getGivenName() != null) {
                properties.put("firstName", acct.getGivenName());
            }
            if (acct.getSn() != null) {
                properties.put("lastName", acct.getSn());
            }
            m.put("properties", properties);

            m.put("sourceType", "zimbra");
            m.put("sourceRef", "dl:"+listMail);

            listMembers.add(m);
        }

    }

    /**
     * Handles parsing a Group (distribution list)
     * @param  group com.zimbra.cs.account.Group
     * @return       A HashMap formatted like:
     *
     * 	[
     * 	  {
     * 		"name": "displayName",
     * 		"email": "group email",
     * 		"members": [
     * 			{
     * 				"email": "member email",
     * 				"properties": {
     * 					"firstName": "sn",
     * 	       			"lastName": "givenName"
     * 				},
     * 				"sourceType": "zimbra",
     * 				"sourceRef": "dl:group-email"
     * 			},
     * 			...
     * 		]
     * 	  },
     * 	  ...
     * 	]
     */
    private HashMap<String, Object> handleGroup(Group group) throws ServiceException {
        HashMap<String, Object> list = new HashMap<String, Object>();

        logger.debug("List: " + group.getMail());

        list.put("name", group.getDisplayName());
        list.put("email", group.getMail());
        list.put("members", new HashSet<HashMap<String, Object>>());

        // Use a reference to add object in handleGroupMembers
        // and we use a Set to avoid duplicates with nested lists
        @SuppressWarnings("unchecked")
        Set<HashMap<String, Object>> listMembers = (Set<HashMap<String, Object>>) list.get("members");

        // Use a set to keep track of nested groups and avoid loops
        // and add ourselves
        Set<String> nestedGroups = Sets.newHashSet();
        nestedGroups.add(group.getMail());

        handleGroupMembers(group, listMembers, nestedGroups);

        return list;
    }

    private boolean shouldInclude(Group group) {
        boolean includeHidden = settings.getBool(UserSettings.DLIST_INCLUDE_HIDE_IN_GAL, true);
        if (group.hideInGal() && !includeHidden) {
            return false;
        }
        return true;
    }

    public List<HashMap<String, Object>> fetch() throws ServiceException {
        List<HashMap<String, Object>> collection = new ArrayList<HashMap<String, Object>>();

        // Always get the lists the account is owner of
        logger.debug("Crawling distribution lists (ownerOf)");
        Set<Group> ownerOf = Group.GroupOwner.getOwnedGroups(account);

        // Get the lists the account is member of if configured
        // and deduplicate lists
        if (settings.getBool(UserSettings.DLIST_MEMBER_OF)) {
            logger.debug("Crawling distribution lists (memberOf)");

            Provisioning prov = Provisioning.getInstance();
            HashMap<String, String> via = new HashMap<String, String>();

            List<Group> memberOf = prov.getGroups(account, settings.getBool(UserSettings.DLIST_DIRECT_MEMBER_ONLY), via);

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
                    HashMap<String, Object> list = handleGroup((Group) entry);
                    collection.add(list);
                }
            }
        } else {

            if (ownerOf != null) {
                for (Group group : ownerOf) {
                    if (shouldInclude(group)) {
                        HashMap<String, Object> list = handleGroup(group);
                        collection.add(list);
                    }
                }
            }

        }

        return collection;
    }
}
