package org.huihoo.ofbiz.smart.service;


import org.huihoo.ofbiz.smart.base.C;
import org.huihoo.ofbiz.smart.base.location.FlexibleLocation;
import org.huihoo.ofbiz.smart.base.util.CommUtil;
import org.huihoo.ofbiz.smart.base.util.Log;
import org.huihoo.ofbiz.smart.base.util.ServiceUtil;
import org.huihoo.ofbiz.smart.entity.Delegator;
import org.huihoo.ofbiz.smart.service.annotation.Service;
import org.huihoo.ofbiz.smart.service.annotation.ServiceDefinition;
import org.huihoo.ofbiz.smart.service.engine.GenericEngine;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServiceDispatcher {
  private final static String TAG = ServiceDispatcher.class.getName();

  private final static Map<String, GenericEngine> ENGINE_MAP = new ConcurrentHashMap<>();

  private final static Map<String, ServiceCallback> SERVICE_CALLBACK_MAP = new ConcurrentHashMap<>();

  private final static Map<String, ServiceModel> SERVICE_CONTEXT_MAP = new ConcurrentHashMap<>();

  private final static String[] INTENAL_ENGINES = {
                                    "org.huihoo.ofbiz.smart.service.engine.EntityAutoEngine",
                                    "org.huihoo.ofbiz.smart.service.engine.StandardJavaEngine"
  };

  private volatile String profile;

  private final Delegator delegator;

  private final String scanResNames;

  public ServiceDispatcher(Delegator delegator) throws GenericServiceException {
    Properties applicationProps = new Properties();
    try {
      applicationProps.load(FlexibleLocation.resolveLocation(C.APPLICATION_CONFIG_NAME).openStream());
    } catch (IOException e) {
      throw new GenericServiceException("Unable to load external properties");
    }

    scanResNames = applicationProps.getProperty(C.SERVICE_SCANNING_NAMES);
    profile = applicationProps.getProperty(C.PROFILE_NAME);

    if (CommUtil.isEmpty(scanResNames)) {
      throw new GenericServiceException("Config[service.scanning.names] is empty.");
    }

    if (delegator == null) {
      Log.w("[ServiceDispatcher.init]:Could not find Delegator instance", TAG);
    }
    
    this.delegator = delegator;

    for (String engineClazzName : INTENAL_ENGINES) {
      registerEngine(engineClazzName);
    }

    loadAndFilterServiceClazz();
  }


  public Map<String, Object> runSync(String serviceName, Map<String, Object> ctx) {
    boolean transaction = false;
    boolean persist = false;
    try {
      if (!C.PROFILE_PRODUCTION.equals(profile)) {
        // In test,develop profile, always load it.
        loadAndFilterServiceClazz();
      }
      ServiceModel serviceModel = SERVICE_CONTEXT_MAP.get(serviceName);
      if (serviceModel == null) {
        String msg = "Unable to locate service[" + serviceName + "]";
        Log.w(msg, TAG);
        return ServiceUtil.returnProplem("SERVICE_NOT_FOUND", msg);
      }
      
      GenericEngine engine = ENGINE_MAP.get(serviceModel.engineName);
      if (engine == null) {
        Log.w("Unsupported ServiceEngine [%s]", TAG, serviceModel.engineName);
        return ServiceUtil.returnProplem("UNSUPPORTED_SERVICE_ENGINE", "Unsupported service ["+ serviceName + "]");
      }
      
      transaction = serviceModel.transaction;
      persist = serviceModel.persist;
      
      if (persist && delegator == null) {
        Log.w("Service [%s] require persist context.", TAG, serviceName);
        return ServiceUtil.returnProplem("UNSUPPORTED_SERVICE_ENGINE", "Unsupported service ["+ serviceName + "]");
      }
      if (persist && transaction) {
        delegator.beginTransaction();
      }
      return engine.runSync(serviceName, ctx);
    } catch (Exception e) {
      if (persist && transaction && delegator != null) {
        delegator.rollback();
      }
      Log.e(e, e.getMessage(), TAG);
      return ServiceUtil.returnProplem("SERVICE_CALL_EXCEPTION", "Calling service["+serviceName+"] has an exception.");
    } finally {
      if (persist && transaction && delegator != null) {
        delegator.endTransaction();
      }
    }
  }

  public void registerEngine(String engineClazzName) {
    if (CommUtil.isEmpty(engineClazzName)) {
      return;
    }
    try {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      Class<?> c = loader.loadClass(engineClazzName);
      Constructor<GenericEngine> cn = CommUtil.cast(c.getConstructor(ServiceDispatcher.class));
      GenericEngine engine = cn.newInstance(this);
      ENGINE_MAP.put(engine.getName(), engine);
    } catch (NoSuchMethodException e) {
      Log.e(e, "Unable to register engine[" + engineClazzName + "]", TAG);
    } catch (ClassNotFoundException e) {
      Log.e(e, "Unable to register engine[" + engineClazzName + "]", TAG);
    } catch (IllegalAccessException e) {
      Log.e(e, "Unable to register engine[" + engineClazzName + "]", TAG);
    } catch (InstantiationException e) {
      Log.e(e, "Unable to register engine[" + engineClazzName + "]", TAG);
    } catch (InvocationTargetException e) {
      Log.e(e, "Unable to register engine[" + engineClazzName + "]", TAG);
    }
  }


  public void registerCallback(String serviceCallbackClazzName) {
    if (CommUtil.isEmpty(serviceCallbackClazzName)) {
      return;
    }
    try {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      Class<?> c = loader.loadClass(serviceCallbackClazzName);
      Constructor<ServiceCallback> cn = CommUtil.cast(c.getConstructor());
      ServiceCallback serviceCallback = cn.newInstance();
      SERVICE_CALLBACK_MAP.put(serviceCallback.getClass().getName(), serviceCallback);
    } catch (NoSuchMethodException e) {
      Log.e(e, "Unable to register serviceCallback[" + serviceCallbackClazzName + "]", TAG);
    } catch (ClassNotFoundException e) {
      Log.e(e, "Unable to register serviceCallback[" + serviceCallbackClazzName + "]", TAG);
    } catch (IllegalAccessException e) {
      Log.e(e, "Unable to register serviceCallback[" + serviceCallbackClazzName + "]", TAG);
    } catch (InstantiationException e) {
      Log.e(e, "Unable to register serviceCallback[" + serviceCallbackClazzName + "]", TAG);
    } catch (InvocationTargetException e) {
      Log.e(e, "Unable to register serviceCallback[" + serviceCallbackClazzName + "]", TAG);
    }
  }
  
  
  public void registerService(ServiceModel sm) {
    if (CommUtil.isEmpty(sm) || CommUtil.isEmpty(sm.name)) {
      return;
    }
    SERVICE_CONTEXT_MAP.put(sm.name, sm);
  }

  public Map<String, ServiceModel> getServiceContextMap() {
    return SERVICE_CONTEXT_MAP;
  }

  // ===================================================================
  // Private Method
  // ===================================================================
  private void loadAndFilterServiceClazz() {
    Set<Class<?>> serviceClasses = new LinkedHashSet<Class<?>>();
    String[] scanResNamesArray = scanResNames.split(",");
    for (String scanResName : scanResNamesArray) {
      serviceClasses.addAll(scanServiceClazz(scanResName, true));
    }
    Log.d("ServiceClazz:" + serviceClasses, TAG);

    for (Class<?> sClazz : serviceClasses) {
      Service serviceAnno = sClazz.getAnnotation(Service.class);
      if (serviceAnno == null) {
        continue;
      }

      Method[] methods = sClazz.getMethods();
      for (Method method : methods) {
        ServiceDefinition sd = method.getAnnotation(ServiceDefinition.class);
        if (sd == null) {
          continue;
        }
        ServiceModel sm = new ServiceModel();
        sm.engineName = sd.type();
        sm.entityName = sd.entityName();
        sm.location = sClazz.getName();
        sm.invoke = method.getName();
        sm.name = sd.name();
        sm.description = sd.description();
        sm.transaction = sd.transaction();
        sm.export = sd.export();
        sm.persist = sd.persist();
        sm.callback = sd.callback();
        sm.requireAuth = sd.requireAuth();
        if (sm.callback != null && sm.callback.length > 0) {
          for (Class<?> clz : sm.callback) {
            try {
              Constructor<ServiceCallback> cn = CommUtil.cast(clz.getConstructor());
              ServiceCallback serviceCallback = cn.newInstance();
              SERVICE_CALLBACK_MAP.put(clz.getName(), serviceCallback);
            } catch(Exception e) {
              Log.w("Unable to load service callback class [%s]", TAG, clz);
            }
          }
        }
        SERVICE_CONTEXT_MAP.put(sm.name, sm);
      }
    }
  }
  
  private Set<Class<?>> scanServiceClazz(String file, boolean recursive) {
    Set<Class<?>> serviceClazzSet = new LinkedHashSet<>();
    if (file.endsWith(".jar")) {
      // TODO jar
    } else {
      String pkgDirName = file.replaceAll("\\.", "/");
      try {
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(pkgDirName);
        while (resources.hasMoreElements()) {
          URL url = resources.nextElement();
          String protocal = url.getProtocol();
          if (file.equals(protocal)) {
            String filePath = URLDecoder.decode(url.getFile(), C.UTF_8);
            findAndAddServiceClazz(file, filePath, recursive, serviceClazzSet);
          }
        }
      } catch (IOException e) {
        Log.w("Could not find resource [" + pkgDirName + "]", TAG);
      }
    }
    return serviceClazzSet;
  }


  private void findAndAddServiceClazz(String pkg, String pgkPath, final boolean recursive, Set<Class<?>> classes) {
    File dir = new File(pgkPath);
    if (!dir.exists() && !dir.isDirectory()) {
      return;
    }

    File[] files = dir.listFiles(new FileFilter() {
      @Override
      public boolean accept(File file) {
        return (recursive && file.isDirectory()) || (file.getName().endsWith(".class"));
      }
    });

    for (File f : files) {
      if (f.isDirectory()) {
        findAndAddServiceClazz(pkg, pgkPath, recursive, classes);
      } else {
        // remove .class suffix
        String clazzName = f.getName().substring(0, f.getName().length() - 6);
        try {
          classes.add(Thread.currentThread().getContextClassLoader().loadClass(pkg + "." + clazzName));
        } catch (ClassNotFoundException e) {
          Log.w("Class[" + pkg + clazzName + "] not found.", TAG);
        }
      }
    }
  }
}
