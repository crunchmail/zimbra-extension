package com.crunchmail.extension;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.extension.ExtensionException;
import com.zimbra.cs.extension.ZimbraExtension;
import com.zimbra.soap.SoapServlet;

import com.crunchmail.extension.soap.CrunchmailDocumentService;

/**
 * Zimbra Serverextension for the Crunchmail-Zimlet
 *
 * @author Nicolas Brisac <nicolas@crunchmail.com>
 */

public class CrunchmailExtension implements ZimbraExtension{

    /**
     * Returns the name of this extension
     *
     * @return Name
     */
    @Override
    public String getName() {
        return "CrunchmailExtension";
    }

    /**
     * Initializes the extension
     *
     * @throws ExtensionException
     * @throws ServiceException
     */
    @Override
    public void init() throws ExtensionException, ServiceException {

        SoapServlet.addService(
            "SoapServlet",
            new CrunchmailDocumentService()
        );

    }

    @Override
    public void destroy() {

    }
}
