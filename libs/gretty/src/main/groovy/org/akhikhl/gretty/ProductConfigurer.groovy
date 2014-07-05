/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import groovy.json.JsonBuilder
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
class ProductConfigurer {

  protected static final Logger log = LoggerFactory.getLogger(ProductConfigurer)

  protected static final String mainClass = 'org.akhikhl.gretty.GrettyStarter'

  protected final Project project
  protected final File baseOutputDir
  protected final String productName
  protected final ProductExtension product
  protected final File outputDir

  protected ServerConfig sconfig
  protected List<WebAppConfig> wconfigs
  protected String jsonConfig
  protected String logbackConfig
  protected Map launchScripts = [:]

  ProductConfigurer(Project project, File baseOutputDir, String productName, ProductExtension product) {
    this.project = project
    this.baseOutputDir = baseOutputDir
    this.productName = productName
    this.product = product
    outputDir = new File(baseOutputDir, productName ?: project.name)
  }

  void configureProduct() {

    def buildProductTask = project.task("buildProduct${productName}", group: 'gretty') {

      dependsOn {
        resolveConfig()
        wconfigs.findResults {
          it.projectPath ? project.project(it.projectPath).tasks.build : null
        }
      }

      inputs.property 'config', {
        resolveConfig()
        jsonConfig
      }

      inputs.property 'logbackConfig', {
        resolveConfig()
        logbackConfig
      }

      inputs.property 'launchScripts', {
        resolveConfig()
        launchScripts
      }

      inputs.files {
        resolveConfig()
        wconfigs.findResults {
          it.projectPath ? null : it.warResourceBase
        }
      }

      inputs.files {
        resolveConfig()
        sconfig.logbackConfigFile ? [ sconfig.logbackConfigFile ] : []
      }

      inputs.files project.configurations.grettyStarter

      inputs.files {
        resolveConfig()
        getRunnerFileCollection()
      }

      outputs.dir outputDir

      doLast {
        resolveConfig()
        writeConfigFiles()
        writeLaunchScripts()
        copyWebappFiles()
        copyStarter()
        copyRunner()
      }
    }

    project.tasks.buildAllProducts.dependsOn buildProductTask

    def archiveProductTask = project.task("archiveProduct${productName}", group: 'gretty') {

      dependsOn buildProductTask

      doLast {
        println "archiving product $productName"
      }
    }

    project.tasks.archiveAllProducts.dependsOn archiveProductTask
  }

  void copyRunner() {
    
    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'runner'))
    
    for(File file in getRunnerFileCollection().files)
      dir.add(file)
      
    dir.cleanup()
  }

  void copyStarter() {
    
    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'starter'))
    
    for(File file in project.configurations.grettyStarter.files)
      dir.add(file)
      
    dir.cleanup()
  }

  void copyWebappFiles() {
    
    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'webapps'))
    
    for(WebAppConfig wconfig in wconfigs) {
      if(ProjectUtils.isSpringBootApp(project, wconfig)) {
        String appDir = ProjectUtils.getWebAppDestinationDirName(project, wconfig)
        def files
        if(wconfig.projectPath) {
          def proj = project.project(wconfig.projectPath)
          for(File webappDir in ProjectUtils.getWebAppDirs(proj))
            for(File f in (webappDir.listFiles() ?: []))
              dir.add(f, appDir)
          def resolvedClassPath = new LinkedHashSet<URL>()
          resolvedClassPath.addAll(ProjectUtils.getClassPathJars(proj, 'runtimeNoSpringBoot'))
          resolvedClassPath.addAll(ProjectUtils.resolveClassPath(proj, wconfig.classPath))
          files = resolvedClassPath.collect { new File(it.path) }
          files -= getRunnerFileCollection().files
        } else {
          def file = wconfig.warResourceBase
          if(!(file instanceof File))
            file = new File(file.toString())
          files = [ file ]
        }
        for(File file in files) {
          if(file.isDirectory())
            for(File f in (file.listFiles() ?: []))
              dir.add(f, appDir + '/WEB-INF/classes')
          else
            dir.add(file, appDir + '/WEB-INF/lib')
        }
      } else {
        def file = wconfig.warResourceBase
        if(!(file instanceof File))
          file = new File(file.toString())
        dir.add(file)
      }
    }
    
    dir.cleanup()
  }

  protected void createLaunchScripts() {

    String shellResolveDir = '#!/bin/bash\n' +
      'SOURCE="${BASH_SOURCE[0]}"\n' +
      'while [ -h "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink\n' +
      'DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"\n' +
      'SOURCE="$(readlink "$SOURCE")"\n' +
      '[[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"\n' +
      'done\n' +
      'DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"\n'

    for(String cmd in ['run', 'start', 'stop', 'restart']) {
      launchScripts[cmd + '.sh'] = shellResolveDir + 'java -Dfile.encoding=UTF8 -cp \"${DIR}/conf/:${DIR}/starter/*\" ' + mainClass + ' ' + cmd + ' $@'
      launchScripts[cmd + '.bat'] = '@java.exe -Dfile.encoding=UTF8 -cp \"%~dp0\\conf\\;%~dp0\\starter\\*\" ' + mainClass + ' ' + cmd + ' %*'
    }
  }

  protected FileCollection getRunnerFileCollection() {
    def servletContainerConfig = ServletContainerConfig.getConfig(sconfig.servletContainer)
    def files
    if(ProjectUtils.anyWebAppUsesSpringBoot(project, wconfigs)) {
      files = project.configurations.grettyNoSpringBoot +
        project.configurations[servletContainerConfig.servletContainerRunnerConfig]
      if(servletContainerConfig.servletContainerType == 'jetty')
        files += project.configurations.grettyRunnerSpringBootJetty
      else if(servletContainerConfig.servletContainerType == 'tomcat')
        files += project.configurations.grettyRunnerSpringBootTomcat
    } else
      files = project.configurations[servletContainerConfig.servletContainerRunnerConfig]        
    files
  }
  
  protected String getSpringBootMainClass() {
    sconfig.springBootMainClass ?: 
    SpringBootMainClassFinder.findMainClass(project) ?: 
    wconfigs.findResult { it.projectPath ? SpringBootMainClassFinder.findMainClass(project.project(it.projectPath)) : null }
  }

  protected resolveConfig() {
    if(sconfig != null)
      return
    FarmConfigurer configurer = new FarmConfigurer(project)
    FarmExtension productFarm = new FarmExtension()
    configurer.configureFarm(productFarm,
      new FarmExtension(logDir: 'logs'),
      new FarmExtension(serverConfig: product.serverConfig, webAppRefs: product.webAppRefs),
      configurer.findProjectFarm(productName)
    )
    sconfig = productFarm.serverConfig
    wconfigs = []
    configurer.resolveWebAppRefs(productFarm.webAppRefs, wconfigs, false)
    for(WebAppConfig wconfig in wconfigs)
      ProjectUtils.prepareToRun(project, wconfig)
    CertificateGenerator.maybeGenerate(project, sconfig)
    jsonConfig = writeConfigToJson()
    if(!sconfig.logbackConfigFile)
      logbackConfig = LogbackUtils.generateLogbackConfig(sconfig)
    createLaunchScripts()
  }

  protected void writeConfigFiles() {
    
    ManagedDirectory dir = new ManagedDirectory(new File(outputDir, 'conf'))

    File configFile = new File(dir.baseDir, 'server.json')
    configFile.parentFile.mkdirs()
    configFile.text = jsonConfig
    dir.registerAdded(configFile)

    if(sconfig.sslKeyStorePath)
      dir.add(sconfig.sslKeyStorePath)

    if(sconfig.sslTrustStorePath)
      dir.add(sconfig.sslTrustStorePath)

    if(sconfig.logbackConfigFile)
      dir.add(sconfig.logbackConfigFile)
    else {
      File logbackConfigFile = new File(dir.baseDir, 'logback.groovy')
      logbackConfigFile.parentFile.mkdirs()
      logbackConfigFile.text = logbackConfig
      dir.registerAdded(logbackConfigFile)
    }

    dir.cleanup()
  }

  protected String writeConfigToJson() {
    def json = new JsonBuilder()
    json {
      writeConfigToJson(delegate)
    }
    json.toPrettyString()
  }

  protected void writeConfigToJson(json) {
    def self = this
    def servletContainerConfig = ServletContainerConfig.getConfig(sconfig.servletContainer)
    json.with {
      productName self.productName ?: project.name
      servletContainer {
        id sconfig.servletContainer
        version servletContainerConfig.servletContainerVersion
        description servletContainerConfig.servletContainerDescription
      }
      serverConfig {
        if(sconfig.host)
          host sconfig.host
        if(sconfig.httpEnabled) {
          httpPort sconfig.httpPort
          if(sconfig.httpIdleTimeout)
            httpIdleTimeout sconfig.httpIdleTimeout
        } else
          httpEnabled false
        if(sconfig.httpsEnabled) {
          httpsEnabled true
          httpsPort sconfig.httpsPort
          if(sconfig.httpsIdleTimeout)
            httpsIdleTimeout sconfig.httpsIdleTimeout
          if(sconfig.sslKeyStorePath)
            sslKeyStorePath 'conf/' + sconfig.sslKeyStorePath.name
          if(sconfig.sslKeyStorePassword)
            sslKeyStorePassword sconfig.sslKeyStorePassword
          if(sconfig.sslKeyManagerPassword)
            sslKeyManagerPassword sconfig.sslKeyManagerPassword
          if(sconfig.sslTrustStorePath)
            sslTrustStorePath 'conf/' + sconfig.sslTrustStorePath.name
          if(sconfig.sslTrustStorePassword)
            sslTrustStorePassword sconfig.sslTrustStorePassword
        }
        if(sconfig.jettyXmlFile)
          jettyXmlFile sconfig.jettyXmlFile.absolutePath
        logbackConfigFile 'conf/' + (sconfig.logbackConfigFile ? sconfig.logbackConfigFile.name : 'logback.groovy')
        if(sconfig.secureRandom != null)
          secureRandom sconfig.secureRandom
      }
      webApps wconfigs.collect { WebAppConfig wconfig ->
        { ->
          contextPath wconfig.contextPath
          if(ProjectUtils.isSpringBootApp(project, wconfig)) {
            springBoot true
            inplaceResourceBase 'webapps/' + ProjectUtils.getWebAppDestinationDirName(project, wconfig)
          }
          else {
            def warFile = wconfig.warResourceBase
            if(!(warFile instanceof File))
              warFile = new File(warFile.toString())
            warResourceBase 'webapps/' + warFile.name            
          }
          if(wconfig.initParameters)
            initParams wconfig.initParameters
          if(wconfig.realm)
            realm wconfig.realm
          if(wconfig.realmConfigFile)
            realmConfigFile wconfig.realmConfigFile.absolutePath
          if(wconfig.jettyEnvXmlFile)
            jettyEnvXmlFile wconfig.jettyEnvXmlFile.absolutePath
          if(wconfig.springBootSources)
            springBootSources wconfig.springBootSources
        }
      }
      if(wconfigs.find { ProjectUtils.isSpringBootApp(project, it) })
        springBootMainClass getSpringBootMainClass()
    } // json
  }

  protected void writeLaunchScripts() {
    launchScripts.each { scriptName, scriptText ->
      File launchScriptFile = new File(outputDir, scriptName)
      launchScriptFile.text = scriptText
      if(scriptName.endsWith('.sh'))
        launchScriptFile.setExecutable(true)
    }
  }
}
