package com.crunchmail.extension.lib;

import com.zimbra.cs.util.BuildInfo;

public class ZimbraVersion extends Version
{
  public static String BUILDNUM = BuildInfo.BUILDNUM;
  public static String HOST     = BuildInfo.HOST;
  public static String DATE     = BuildInfo.DATE;
  public static String PLATFORM = BuildInfo.PLATFORM;
  public static String FULL_VERSION = BuildInfo.FULL_VERSION;

  public ZimbraVersion(int major, int minor, int micro)
  {
    super(major, minor, micro);
  }

  public static ZimbraVersion current = new ZimbraVersion(
    Integer.valueOf(BuildInfo.MAJORVERSION),
    Integer.valueOf(BuildInfo.MINORVERSION),
    Integer.valueOf(BuildInfo.MICROVERSION)
  );

  public static void restoreVersion()
  {
    current = new ZimbraVersion(
      Integer.valueOf(BuildInfo.MAJORVERSION),
      Integer.valueOf(BuildInfo.MINORVERSION),
      Integer.valueOf(BuildInfo.MICROVERSION)
    );
  }

  public ZimbraVersion(String zimbraVersion)
  {
    super(zimbraVersion);
  }
}
