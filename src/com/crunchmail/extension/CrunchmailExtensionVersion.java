package com.crunchmail.extension;

import com.crunchmail.extension.lib.Version;

public class CrunchmailExtensionVersion
{
  public static final    Version current = new Version(BuildInfo.VERSION);
  public static Version target  = new Version(0, 0, 0);

  static
  {
    String implementationVersion = CrunchmailExtensionVersion.class.getPackage().getImplementationVersion();
    if (implementationVersion != null && !implementationVersion.isEmpty())
    {
      target = new Version(implementationVersion);
    }
  }

  public static void main(String args[])
  {
    System.out.println("crunchmail_extension_version: " + current.toString());
    System.out.println("crunchmail_extension_commit: " + BuildInfo.COMMIT);
    System.out.println("target_zimbra_version: " + target.toString());

    System.exit(0);
  }
}
