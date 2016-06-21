package com.crunchmail.extension;

import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.extension.ExtensionException;
import com.zimbra.cs.extension.ZimbraExtension;
import com.zimbra.cs.extension.ExtensionDispatcherServlet;
import com.zimbra.soap.SoapServlet;

import com.crunchmail.extension.lib.ZimbraVersion;
import com.crunchmail.extension.soap.CrunchmailDocumentService;
import com.crunchmail.extension.http.handlers.RemoteFolderHandler;

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
        return CrunchmailExtension.class.getPackage().getSpecificationTitle();
    }

    /**
    * Initializes the extension
    *
    * @throws ExtensionException
    * @throws ServiceException
    */
    @Override
    public void init() throws ExtensionException, ServiceException {

        ZimbraLog.mailbox.info(
            "Loading " + getName() + ". Version: " +
            CrunchmailExtensionVersion.current +
            ". Commit: " +
            BuildInfo.COMMIT
        );

        if (!ZimbraVersion.current.equals(CrunchmailExtensionVersion.target))
        {
            throw new RuntimeException("Crunchmail Extension - Zimbra version mismatch, extension is built for Zimbra: " + CrunchmailExtensionVersion.target.toString());
        }

        SoapServlet.addService(
        "SoapServlet",
        new CrunchmailDocumentService()
        );

        ExtensionDispatcherServlet.register(
            this, new RemoteFolderHandler()
        );

    }

    @Override
    public void destroy() {

    }
}
