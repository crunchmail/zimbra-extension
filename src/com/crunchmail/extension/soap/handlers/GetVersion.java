package com.crunchmail.extension.soap.handlers;

import java.util.Map;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.soap.DocumentHandler;
import com.zimbra.soap.ZimbraSoapContext;

import com.crunchmail.extension.CrunchmailExtensionVersion;
import com.crunchmail.extension.BuildInfo;

public class GetVersion extends DocumentHandler {

    /**
     * Handle the SOAP the request
     * @param Element request The request
     * @param MapwString, Object> context The soap context
     * @return Element response
     * @throws ServiceException
     */
    @Override
    public Element handle(Element request, Map<String, Object> context) throws ServiceException {

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Element response = zsc.createElement("GetVersionResponse");

        response.addAttribute("version", CrunchmailExtensionVersion.current.toString());
        response.addAttribute("commit", BuildInfo.COMMIT);
        response.addAttribute("commit_short", BuildInfo.COMMIT_SHORT);

        return response;
    }

}
