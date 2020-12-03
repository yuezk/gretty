package org.akhikhl.gretty;

import static java.util.Objects.requireNonNull;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

public class AppServletInitializer extends SpringBootServletInitializer {

  private String springBootMainClass;

  @Override
  public void onStartup(ServletContext servletContext) throws ServletException {
    springBootMainClass = requireNonNull(servletContext.getInitParameter("GRETTY_SPRING_BOOT_MAIN_CLASS"));
    super.onStartup(servletContext);
  }

  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    try {
      return application.sources(Class.forName(springBootMainClass, true, AppServletInitializer.class.getClassLoader()));
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      return application;
    }
  }
}