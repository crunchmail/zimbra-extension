package com.crunchmail.extension.soap;

import com.zimbra.soap.DocumentDispatcher;
import com.zimbra.soap.DocumentService;
import org.dom4j.Namespace;
import org.dom4j.QName;

import com.crunchmail.extension.soap.handlers.*;

/**
 * Document part of the Zimbra server extension
 *
 * @author Nicolas Brisac <nicolas@crunchmail.com>
 */

public class CrunchmailDocumentService implements DocumentService{

    /**
     * The namespace of the whole document service
     */
    public static final Namespace namespace = Namespace.get("urn:crunchmail");

    /**
     * Register the document handlers to the dispatcher
     *
     * @param dispatcher Zimbra's document dispatcher
     */
    @Override
    public void registerHandlers(DocumentDispatcher dispatcher) {

        dispatcher.registerHandler(
            QName.get("GetVersionRequest", namespace),
            new GetVersion()
        );

        dispatcher.registerHandler(
            QName.get("GetContactsRequest", namespace),
            new GetContacts()
        );

        dispatcher.registerHandler(
            QName.get("GetContactsTreeRequest", namespace),
            new GetContactsTree()
        );

    }
}
