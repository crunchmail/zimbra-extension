package com.crunchmail.extension.http.handlers;

import java.util.List;
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


public class RemoteFolderHandler extends ExtensionHttpHandler {

    private HttpServletResponse response;
    private OperationContext octxt;

    private Logger logger = new Logger(true);

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
                            octxt = new OperationContext(authToken.getAccount());
                            auth = true;
                        }
                    } catch (ServiceException|AuthTokenException e) {
                        logger.error("RemoteFolderHandler - Token Authentication Exception: " + e.getMessage());
                    }
                }
            }
        }

        return auth;
    }

    private boolean isAuthorized(Mailbox mbox, ItemId iid) throws ServiceException {
        boolean authorized = false;
        try {
            mbox.getFolderTree(octxt, iid, true);
            authorized = true;
        } catch (ServiceException e) {
            if (e.getCode().equals(ServiceException.PERM_DENIED)) {
                // if it is a permission denied, fail gracefully
                logger.error("RemoteFolderHandler - Can't access requested item ("+ mbox.getAccount().getId() +":"+ iid.getId() +") with account "+ octxt.getAuthenticatedUser().getId() +". Permission denied.");
            } else {
                // re-raise
                throw e;
            }
        }

        return authorized;
    }

    private void processRequest(HttpServletRequest request) throws ServletException, IOException, ServiceException {
        Gson gson = new Gson();

        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = request.getReader().readLine()) != null) {
           sb.append(s);
        }

        Type type = new TypeToken<Map<String, String>>(){}.getType();
        Map<String, String> req = gson.fromJson(sb.toString(), type);

        // Validate the request
        if (!req.containsKey("tree") || !req.containsKey("account") || !req.containsKey("item") || !req.containsKey("include_fields")) {
           sendResponse("Request badly formatted. Missing required attributes.", HttpServletResponse.SC_BAD_REQUEST);
        } else {

            try {
                logger.debug("account");
                Account account = Provisioning.getInstance().getAccount(req.get("account"));
                logger.debug("mbox");
                Mailbox mbox = MailboxManager.getInstance().getMailboxByAccountId(req.get("account"));
                logger.debug("item");
                ItemId iid = new ItemId(req.get("account"), Integer.parseInt(req.get("item")));

                if (isAuthorized(mbox, iid)) {
                    String[] includeFieldsDefault = req.get("include_fields").split(",");
                    ContactsFetcher fetcher = new ContactsFetcher(mbox, account, true, includeFieldsDefault, true);

                    if (Boolean.parseBoolean(req.get("tree"))) {
                        Map<String, Object> tree = fetcher.fetchTree(iid);
                        sendResponse(tree);
                    } else {
                        Map<String, List<HashMap<String, Object>>> collection = fetcher.fetchCollection(iid);
                        sendResponse(collection);
                    }
                } else {
                    sendResponse("Not authorized to access requested item.", HttpServletResponse.SC_UNAUTHORIZED);
                }

            } catch (Exception e) {
                logger.error("RemoteFolderHandler - Exception while processing request: " + e);
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
        sendResponse(payload, HttpServletResponse.SC_OK);
    }

    private void sendResponse(Object payload, int status) throws IOException{
        Gson gson = new Gson();
        response.setContentType("application/json");
        response.setStatus(status);
        response.getOutputStream().print(gson.toJson(payload));
        response.getOutputStream().flush();
    }

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        response = resp;

        try {
            if (authenticateRequest(req)) {
                processRequest(req);
            } else {
                sendResponse("Token authentication failed.", HttpServletResponse.SC_UNAUTHORIZED);
            }
        } catch (ServiceException e) {
            logger.error("RemoteFolderHandler - Exception while processing request: " + e);
        }
    }
}
