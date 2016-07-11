package com.crunchmail.extension.http.handlers;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.lang.reflect.Type;
import java.io.IOException;
import java.io.BufferedReader;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.extension.ExtensionHttpHandler;
import com.zimbra.cs.extension.ZimbraExtension;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthTokenException;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.OperationContext;

import com.crunchmail.extension.Logger;
import com.crunchmail.extension.ContactsFetcher;
import com.crunchmail.extension.ContactsCrawler.Collection;
import com.crunchmail.extension.ContactsCrawler.Tree;
import com.crunchmail.extension.ContactsCrawler.RemoteResponse;


public class RemoteFolderHandler extends ExtensionHttpHandler {

    class FolderRequest {
        public boolean tree = false;
        public String account;
        public int item = 0;
        public String[] includeFields;
        public Set<String> existing = new HashSet<String>();
        public boolean debug = false;

        public boolean validate() {
            return (account != null) && (item != 0) && (includeFields != null && includeFields.length != 0);
        }
    }

    private HttpServletResponse mResponse;
    private OperationContext mOctxt;

    private Logger mLogger = new Logger(true);

    // Handler will be available at http://{my-zimbra-server-url}/service/extension/{path}
    public String getPath() {
        return "/crunchmail/getremotefolder";
    }

    public void init(ZimbraExtension ext) throws ServiceException {
        super.init(ext);
        // your initialization
    }

    private boolean authenticateRequest(HttpServletRequest req) {
        boolean auth = false;

        String authHeader = req.getHeader("Authorization");
        if (authHeader != null) {

            StringTokenizer st = new StringTokenizer(authHeader);
            if (st.hasMoreTokens()) {
                String type = st.nextToken();

                if (type.equalsIgnoreCase("Token")) {
                    try {
                        String token = st.nextToken();
                        AuthToken authToken = AuthToken.getAuthToken(token);
                        if (!authToken.isExpired() && authToken.isZimbraUser()) {
                            mOctxt = new OperationContext(authToken.getAccount());
                            auth = true;
                        }
                    } catch (ServiceException|AuthTokenException e) {
                        mLogger.error("RemoteFolderHandler - Token Authentication Exception: " + e.getMessage());
                    }
                }
            }
        }

        return auth;
    }

    private boolean isAuthorized(Mailbox mbox, ItemId iid) throws ServiceException {
        try {
            // will throw an exception if current user does not have sufficient permissions on owner's object
            // IMPORTANT: needs false as last argument for exception to be thrown
            mbox.getFolderTree(mOctxt, iid, false);
        } catch (ServiceException e) {
            if (e.getCode().equals(ServiceException.PERM_DENIED)) {
                // if it is a permission denied, fail gracefully
                mLogger.error("RemoteFolderHandler - Can't access requested item ("+ mbox.getAccount().getId() +":"+ iid.getId() +") with account "+ mOctxt.getAuthenticatedUser().getId() +". Permission denied.");
                return false;
            } else {
                // re-raise
                throw e;
            }
        }

        return true;
    }

    private void processRequest(HttpServletRequest request) throws ServletException, IOException, ServiceException {
        Gson gson = new Gson();

        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = request.getReader().readLine()) != null) {
           sb.append(s);
        }

        FolderRequest req = gson.fromJson(sb.toString(), FolderRequest.class);

        // Validate the request
        if (!req.validate()) {
           sendResponse("Request badly formatted. Missing required attributes.", HttpServletResponse.SC_BAD_REQUEST);
        } else {

            try {
                mLogger.debug("Remote server asking for folder content (account: "+req.account+", item: "+req.item+", remote account: "+mOctxt.getAuthenticatedUser().getId()+")");
                Account account = Provisioning.getInstance().getAccount(req.account);
                Mailbox mbox = MailboxManager.getInstance().getMailboxByAccountId(req.account);
                ItemId iid = new ItemId(req.account, req.item);

                if (isAuthorized(mbox, iid)) {
                    ContactsFetcher fetcher = new ContactsFetcher(mbox, account, req.debug, req.existing, req.includeFields, true);

                    if (req.tree) {
                        Tree tree = fetcher.fetchTree(iid);
                        // sendResponse(tree);
                        RemoteResponse resp = fetcher.makeResponse(tree);
                        sendResponse(resp);
                    } else {
                        Collection collection = fetcher.fetchCollection(iid);
                        // sendResponse(collection);
                        RemoteResponse resp = fetcher.makeResponse(collection);
                        sendResponse(resp);
                    }
                } else {
                    sendResponse("Not authorized to access requested item.", HttpServletResponse.SC_UNAUTHORIZED);
                }

            } catch (Exception e) {
                mLogger.error("RemoteFolderHandler - Exception while processing request: " + e);
                sendResponse("Error while processing request.", HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            }
        }
    }

    private void sendResponse(Object payload) throws IOException{
        sendResponse(payload, HttpServletResponse.SC_OK);
    }

    private void sendResponse(String error, int status) throws IOException{
        Map<String, String> payload = new HashMap<String, String>();
        payload.put("error", error);
        sendResponse(payload, status);
    }

    private void sendResponse(Object payload, int status) throws IOException{
        Gson gson = new Gson();
        mResponse.setContentType("application/json");
        mResponse.setStatus(status);
        mResponse.getOutputStream().print(gson.toJson(payload));
        mResponse.getOutputStream().flush();
    }

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        mResponse = resp;

        try {
            if (authenticateRequest(req)) {
                processRequest(req);
            } else {
                sendResponse("Token authentication failed.", HttpServletResponse.SC_UNAUTHORIZED);
            }
        } catch (ServiceException e) {
            mLogger.error("RemoteFolderHandler - Exception while processing request: " + e);
        }
    }
}
