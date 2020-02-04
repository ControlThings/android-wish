// WishBridge.aidl
package fi.ct.bridge;

// Declare any non-default types here with import statements

interface AppBridge {

  void sendCoreToApp(in byte[] data);
}

