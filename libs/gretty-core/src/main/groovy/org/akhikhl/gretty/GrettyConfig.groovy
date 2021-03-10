/*
 * Gretty
 *
 * Copyright (C) 2013-2015 Andrey Hihlovskiy and contributors.
 *
 * See the file "LICENSE" for copying and usage permission.
 * See the file "CONTRIBUTORS" for complete list of contributors.
 */
package org.akhikhl.gretty

/**
 *
 * @author akhikhl
 */
class GrettyConfig {

  @Delegate
  protected ServerConfig serverConfig = new ServerConfig()

  @Delegate
  protected WebAppConfig webAppConfig = new WebAppConfig()
}
