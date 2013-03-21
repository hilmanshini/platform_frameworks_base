/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IStopUserCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.RestrictionEntry;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IUserManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class UserManagerService extends IUserManager.Stub {

    private static final String LOG_TAG = "UserManagerService";

    private static final boolean DBG = false;

    private static final String TAG_NAME = "name";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_USER_VERSION = "version";
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_MULTIPLE = "m";

    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";

    private static final String RESTRICTIONS_FILE_PREFIX = "res_";

    private static final int MIN_USER_ID = 10;

    private static final int USER_VERSION = 2;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    private final Context mContext;
    private final PackageManagerService mPm;
    private final Object mInstallLock;
    private final Object mPackagesLock;

    private final Handler mHandler;

    private final File mUsersDir;
    private final File mUserListFile;
    private final File mBaseUserPath;

    private final SparseArray<UserInfo> mUsers = new SparseArray<UserInfo>();
    private final SparseArray<Bundle> mUserRestrictions = new SparseArray<Bundle>();

    /**
     * Set of user IDs being actively removed. Removed IDs linger in this set
     * for several seconds to work around a VFS caching issue.
     */
    // @GuardedBy("mPackagesLock")
    private final SparseBooleanArray mRemovingUserIds = new SparseBooleanArray();

    private int[] mUserIds;
    private boolean mGuestEnabled;
    private int mNextSerialNumber;
    private int mUserVersion = 0;

    private static UserManagerService sInstance;

    public static UserManagerService getInstance() {
        synchronized (UserManagerService.class) {
            return sInstance;
        }
    }

    /**
     * Available for testing purposes.
     */
    UserManagerService(File dataDir, File baseUserPath) {
        this(null, null, new Object(), new Object(), dataDir, baseUserPath);
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm,
            Object installLock, Object packagesLock) {
        this(context, pm, installLock, packagesLock,
                Environment.getDataDirectory(),
                new File(Environment.getDataDirectory(), "user"));
    }

    /**
     * Available for testing purposes.
     */
    private UserManagerService(Context context, PackageManagerService pm,
            Object installLock, Object packagesLock,
            File dataDir, File baseUserPath) {
        mContext = context;
        mPm = pm;
        mInstallLock = installLock;
        mPackagesLock = packagesLock;
        mHandler = new Handler();
        synchronized (mInstallLock) {
            synchronized (mPackagesLock) {
                mUsersDir = new File(dataDir, USER_INFO_DIR);
                mUsersDir.mkdirs();
                // Make zeroth user directory, for services to migrate their files to that location
                File userZeroDir = new File(mUsersDir, "0");
                userZeroDir.mkdirs();
                mBaseUserPath = baseUserPath;
                FileUtils.setPermissions(mUsersDir.toString(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG
                        |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                        -1, -1);
                mUserListFile = new File(mUsersDir, USER_LIST_FILENAME);
                readUserListLocked();
                // Prune out any partially created/partially removed users.
                ArrayList<UserInfo> partials = new ArrayList<UserInfo>();
                for (int i = 0; i < mUsers.size(); i++) {
                    UserInfo ui = mUsers.valueAt(i);
                    if (ui.partial && i != 0) {
                        partials.add(ui);
                    }
                }
                for (int i = 0; i < partials.size(); i++) {
                    UserInfo ui = partials.get(i);
                    Slog.w(LOG_TAG, "Removing partially created user #" + i
                            + " (name=" + ui.name + ")");
                    removeUserStateLocked(ui.id);
                }
                sInstance = this;
            }
        }
    }

    @Override
    public List<UserInfo> getUsers(boolean excludeDying) {
        checkManageUsersPermission("query users");
        synchronized (mPackagesLock) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo ui = mUsers.valueAt(i);
                if (ui.partial) {
                    continue;
                }
                if (!excludeDying || !mRemovingUserIds.get(ui.id)) {
                    users.add(ui);
                }
            }
            return users;
        }
    }

    @Override
    public UserInfo getUserInfo(int userId) {
        checkManageUsersPermission("query user");
        synchronized (mPackagesLock) {
            return getUserInfoLocked(userId);
        }
    }

    /*
     * Should be locked on mUsers before calling this.
     */
    private UserInfo getUserInfoLocked(int userId) {
        UserInfo ui = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (ui != null && ui.partial && !mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        }
        return ui;
    }

    public boolean exists(int userId) {
        synchronized (mPackagesLock) {
            return ArrayUtils.contains(mUserIds, userId);
        }
    }

    @Override
    public void setUserName(int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
                return;
            }
            if (name != null && !name.equals(info.name)) {
                info.name = name;
                writeUserLocked(info);
                changed = true;
            }
        }
        if (changed) {
            sendUserInfoChangedBroadcast(userId);
        }
    }

    @Override
    public void setUserIcon(int userId, Bitmap bitmap) {
        checkManageUsersPermission("update users");
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "setUserIcon: unknown user #" + userId);
                return;
            }
            writeBitmapLocked(info, bitmap);
            writeUserLocked(info);
        }
        sendUserInfoChangedBroadcast(userId);
    }

    private void sendUserInfoChangedBroadcast(int userId) {
        Intent changedIntent = new Intent(Intent.ACTION_USER_INFO_CHANGED);
        changedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        changedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changedIntent, new UserHandle(userId));
    }

    @Override
    public Bitmap getUserIcon(int userId) {
        checkManageUsersPermission("read users");
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "getUserIcon: unknown user #" + userId);
                return null;
            }
            if (info.iconPath == null) {
                return null;
            }
            return BitmapFactory.decodeFile(info.iconPath);
        }
    }

    @Override
    public void setGuestEnabled(boolean enable) {
        checkManageUsersPermission("enable guest users");
        synchronized (mPackagesLock) {
            if (mGuestEnabled != enable) {
                mGuestEnabled = enable;
                // Erase any guest user that currently exists
                for (int i = 0; i < mUsers.size(); i++) {
                    UserInfo user = mUsers.valueAt(i);
                    if (!user.partial && user.isGuest()) {
                        if (!enable) {
                            removeUser(user.id);
                        }
                        return;
                    }
                }
                // No guest was found
                if (enable) {
                    createUser("Guest", UserInfo.FLAG_GUEST);
                }
            }
        }
    }

    @Override
    public boolean isGuestEnabled() {
        synchronized (mPackagesLock) {
            return mGuestEnabled;
        }
    }

    @Override
    public void wipeUser(int userHandle) {
        checkManageUsersPermission("wipe user");
        // TODO:
    }

    public void makeInitialized(int userId) {
        checkManageUsersPermission("makeInitialized");
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
            }
            if ((info.flags&UserInfo.FLAG_INITIALIZED) == 0) {
                info.flags |= UserInfo.FLAG_INITIALIZED;
                writeUserLocked(info);
            }
        }
    }

    @Override
    public Bundle getUserRestrictions(int userId) {
        // checkManageUsersPermission("getUserRestrictions");

        synchronized (mPackagesLock) {
            Bundle restrictions = mUserRestrictions.get(userId);
            return restrictions != null ? restrictions : Bundle.EMPTY;
        }
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, int userId) {
        checkManageUsersPermission("setUserRestrictions");

        synchronized (mPackagesLock) {
            mUserRestrictions.get(userId).putAll(restrictions);
            writeUserLocked(mUsers.get(userId));
        }
    }

    /**
     * Check if we've hit the limit of how many users can be created.
     */
    private boolean isUserLimitReachedLocked() {
        int nUsers = mUsers.size();
        return nUsers >= UserManager.getMaxSupportedUsers();
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission.MANAGE_USERS MANAGE_USERS}
     * permission can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static final void checkManageUsersPermission(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0
                && ActivityManager.checkComponentPermission(
                        android.Manifest.permission.MANAGE_USERS,
                        uid, -1, true) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    private void writeBitmapLocked(UserInfo info, Bitmap bitmap) {
        try {
            File dir = new File(mUsersDir, Integer.toString(info.id));
            File file = new File(dir, USER_PHOTO_FILENAME);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            FileOutputStream os;
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, os = new FileOutputStream(file))) {
                info.iconPath = file.getAbsolutePath();
            }
            try {
                os.close();
            } catch (IOException ioe) {
                // What the ... !
            }
        } catch (FileNotFoundException e) {
            Slog.w(LOG_TAG, "Error setting photo for user ", e);
        }
    }

    /**
     * Returns an array of user ids. This array is cached here for quick access, so do not modify or
     * cache it elsewhere.
     * @return the array of user ids.
     */
    public int[] getUserIds() {
        synchronized (mPackagesLock) {
            return mUserIds;
        }
    }

    int[] getUserIdsLPr() {
        return mUserIds;
    }

    private void readUserList() {
        synchronized (mPackagesLock) {
            readUserListLocked();
        }
    }

    private void readUserListLocked() {
        mGuestEnabled = false;
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLocked();
            return;
        }
        FileInputStream fis = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fis = userListFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read user list");
                fallbackToSingleUserLocked();
                return;
            }

            mNextSerialNumber = -1;
            if (parser.getName().equals(TAG_USERS)) {
                String lastSerialNumber = parser.getAttributeValue(null, ATTR_NEXT_SERIAL_NO);
                if (lastSerialNumber != null) {
                    mNextSerialNumber = Integer.parseInt(lastSerialNumber);
                }
                String versionNumber = parser.getAttributeValue(null, ATTR_USER_VERSION);
                if (versionNumber != null) {
                    mUserVersion = Integer.parseInt(versionNumber);
                }
            }

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    UserInfo user = readUserLocked(Integer.parseInt(id));

                    if (user != null) {
                        mUsers.put(user.id, user);
                        if (user.isGuest()) {
                            mGuestEnabled = true;
                        }
                        if (mNextSerialNumber < 0 || mNextSerialNumber <= user.id) {
                            mNextSerialNumber = user.id + 1;
                        }
                    }
                }
            }
            updateUserIdsLocked();
            upgradeIfNecessary();
        } catch (IOException ioe) {
            fallbackToSingleUserLocked();
        } catch (XmlPullParserException pe) {
            fallbackToSingleUserLocked();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     */
    private void upgradeIfNecessary() {
        int userVersion = mUserVersion;
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            UserInfo user = mUsers.get(UserHandle.USER_OWNER);
            if ("Primary".equals(user.name)) {
                user.name = mContext.getResources().getString(com.android.internal.R.string.owner_name);
                writeUserLocked(user);
            }
            userVersion = 1;
        }

        if (userVersion < 2) {
            // Owner should be marked as initialized
            UserInfo user = mUsers.get(UserHandle.USER_OWNER);
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                user.flags |= UserInfo.FLAG_INITIALIZED;
                writeUserLocked(user);
            }
            userVersion = 2;
        }

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            mUserVersion = userVersion;
            writeUserListLocked();
        }
    }

    private void fallbackToSingleUserLocked() {
        // Create the primary user
        UserInfo primary = new UserInfo(UserHandle.USER_OWNER,
                mContext.getResources().getString(com.android.internal.R.string.owner_name), null,
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY | UserInfo.FLAG_INITIALIZED);
        mUsers.put(0, primary);
        mNextSerialNumber = MIN_USER_ID;

        Bundle restrictions = new Bundle();
        initRestrictionsToDefaults(restrictions);
        mUserRestrictions.append(UserHandle.USER_OWNER, restrictions);

        updateUserIdsLocked();

        writeUserListLocked();
        writeUserLocked(primary);
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    private void writeUserLocked(UserInfo userInfo) {
        FileOutputStream fos = null;
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userInfo.id + ".xml"));
        try {
            fos = userFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USER);
            serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
            serializer.attribute(null, ATTR_SERIAL_NO, Integer.toString(userInfo.serialNumber));
            serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));
            serializer.attribute(null, ATTR_CREATION_TIME, Long.toString(userInfo.creationTime));
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_TIME,
                    Long.toString(userInfo.lastLoggedInTime));
            if (userInfo.iconPath != null) {
                serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
            }
            if (userInfo.partial) {
                serializer.attribute(null, ATTR_PARTIAL, "true");
            }

            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);

            Bundle restrictions = mUserRestrictions.get(userInfo.id);
            if (restrictions != null) {
                serializer.startTag(null, TAG_RESTRICTIONS);
                writeBoolean(serializer, restrictions, UserManager.ALLOW_CONFIG_WIFI);
                writeBoolean(serializer, restrictions, UserManager.ALLOW_MODIFY_ACCOUNTS);
                writeBoolean(serializer, restrictions, UserManager.ALLOW_INSTALL_APPS);
                writeBoolean(serializer, restrictions, UserManager.ALLOW_UNINSTALL_APPS);
                writeBoolean(serializer, restrictions, UserManager.ALLOW_CONFIG_LOCATION_ACCESS);
                serializer.endTag(null, TAG_RESTRICTIONS);
            }
            serializer.endTag(null, TAG_USER);

            serializer.endDocument();
            userFile.finishWrite(fos);
        } catch (Exception ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userInfo.id + "\n" + ioe);
            userFile.failWrite(fos);
        }
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    private void writeUserListLocked() {
        FileOutputStream fos = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fos = userListFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USERS);
            serializer.attribute(null, ATTR_NEXT_SERIAL_NO, Integer.toString(mNextSerialNumber));
            serializer.attribute(null, ATTR_USER_VERSION, Integer.toString(mUserVersion));

            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo user = mUsers.valueAt(i);
                serializer.startTag(null, TAG_USER);
                serializer.attribute(null, ATTR_ID, Integer.toString(user.id));
                serializer.endTag(null, TAG_USER);
            }

            serializer.endTag(null, TAG_USERS);

            serializer.endDocument();
            userListFile.finishWrite(fos);
        } catch (Exception e) {
            userListFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing user list");
        }
    }

    private UserInfo readUserLocked(int id) {
        int flags = 0;
        int serialNumber = id;
        String name = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        boolean partial = false;
        Bundle restrictions = new Bundle();
        initRestrictionsToDefaults(restrictions);

        FileInputStream fis = null;
        try {
            AtomicFile userFile =
                    new AtomicFile(new File(mUsersDir, Integer.toString(id) + ".xml"));
            fis = userFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read user " + id);
                return null;
            }

            if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
                int storedId = readIntAttribute(parser, ATTR_ID, -1);
                if (storedId != id) {
                    Slog.e(LOG_TAG, "User id does not match the file name");
                    return null;
                }
                serialNumber = readIntAttribute(parser, ATTR_SERIAL_NO, id);
                flags = readIntAttribute(parser, ATTR_FLAGS, 0);
                iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH);
                creationTime = readLongAttribute(parser, ATTR_CREATION_TIME, 0);
                lastLoggedInTime = readLongAttribute(parser, ATTR_LAST_LOGGED_IN_TIME, 0);
                String valueString = parser.getAttributeValue(null, ATTR_PARTIAL);
                if ("true".equals(valueString)) {
                    partial = true;
                }

                int outerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                       && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }
                    String tag = parser.getName();
                    if (TAG_NAME.equals(tag)) {
                        type = parser.next();
                        if (type == XmlPullParser.TEXT) {
                            name = parser.getText();
                        }
                    } else if (TAG_RESTRICTIONS.equals(tag)) {
                        readBoolean(parser, restrictions, UserManager.ALLOW_CONFIG_WIFI);
                        readBoolean(parser, restrictions, UserManager.ALLOW_MODIFY_ACCOUNTS);
                        readBoolean(parser, restrictions, UserManager.ALLOW_INSTALL_APPS);
                        readBoolean(parser, restrictions, UserManager.ALLOW_UNINSTALL_APPS);
                        readBoolean(parser, restrictions, UserManager.ALLOW_CONFIG_LOCATION_ACCESS);
                    }
                }
            }

            UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
            userInfo.serialNumber = serialNumber;
            userInfo.creationTime = creationTime;
            userInfo.lastLoggedInTime = lastLoggedInTime;
            userInfo.partial = partial;
            mUserRestrictions.append(id, restrictions);
            return userInfo;

        } catch (IOException ioe) {
        } catch (XmlPullParserException pe) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        return null;
    }

    private void readBoolean(XmlPullParser parser, Bundle restrictions,
            String restrictionKey) {
        String value = parser.getAttributeValue(null, restrictionKey);
        restrictions.putBoolean(restrictionKey, value == null ? true : Boolean.parseBoolean(value));
    }

    private void writeBoolean(XmlSerializer xml, Bundle restrictions, String restrictionKey)
            throws IOException {
        if (restrictions.containsKey(restrictionKey)) {
            xml.attribute(null, restrictionKey,
                    Boolean.toString(restrictions.getBoolean(restrictionKey)));
        }
    }

    private void initRestrictionsToDefaults(Bundle restrictions) {
        restrictions.putBoolean(UserManager.ALLOW_CONFIG_WIFI, true);
        restrictions.putBoolean(UserManager.ALLOW_MODIFY_ACCOUNTS, true);
        restrictions.putBoolean(UserManager.ALLOW_INSTALL_APPS, true);
        restrictions.putBoolean(UserManager.ALLOW_UNINSTALL_APPS, true);
        restrictions.putBoolean(UserManager.ALLOW_CONFIG_LOCATION_ACCESS, true);
    }

    private int readIntAttribute(XmlPullParser parser, String attr, int defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) return defaultValue;
        try {
            return Integer.parseInt(valueString);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    private long readLongAttribute(XmlPullParser parser, String attr, long defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) return defaultValue;
        try {
            return Long.parseLong(valueString);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    @Override
    public UserInfo createUser(String name, int flags) {
        checkManageUsersPermission("Only the system can create users");

        final long ident = Binder.clearCallingIdentity();
        final UserInfo userInfo;
        try {
            synchronized (mInstallLock) {
                synchronized (mPackagesLock) {
                    if (isUserLimitReachedLocked()) return null;
                    int userId = getNextAvailableIdLocked();
                    userInfo = new UserInfo(userId, name, null, flags);
                    File userPath = new File(mBaseUserPath, Integer.toString(userId));
                    userInfo.serialNumber = mNextSerialNumber++;
                    long now = System.currentTimeMillis();
                    userInfo.creationTime = (now > EPOCH_PLUS_30_YEARS) ? now : 0;
                    userInfo.partial = true;
                    Environment.getUserSystemDirectory(userInfo.id).mkdirs();
                    mUsers.put(userId, userInfo);
                    writeUserListLocked();
                    writeUserLocked(userInfo);
                    mPm.createNewUserLILPw(userId, userPath);
                    userInfo.partial = false;
                    writeUserLocked(userInfo);
                    updateUserIdsLocked();
                    Bundle restrictions = new Bundle();
                    initRestrictionsToDefaults(restrictions);
                    mUserRestrictions.append(userId, restrictions);
                }
            }
            if (userInfo != null) {
                Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
                addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userInfo.id);
                mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                        android.Manifest.permission.MANAGE_USERS);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return userInfo;
    }

    /**
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param id the user's id
     */
    public boolean removeUser(int userHandle) {
        checkManageUsersPermission("Only the system can remove users");
        final UserInfo user;
        synchronized (mPackagesLock) {
            user = mUsers.get(userHandle);
            if (userHandle == 0 || user == null) {
                return false;
            }
            mRemovingUserIds.put(userHandle, true);
            // Set this to a partially created user, so that the user will be purged
            // on next startup, in case the runtime stops now before stopping and
            // removing the user completely.
            user.partial = true;
            writeUserLocked(user);
        }
        if (DBG) Slog.i(LOG_TAG, "Stopping user " + userHandle);
        int res;
        try {
            res = ActivityManagerNative.getDefault().stopUser(userHandle,
                    new IStopUserCallback.Stub() {
                        @Override
                        public void userStopped(int userId) {
                            finishRemoveUser(userId);
                        }
                        @Override
                        public void userStopAborted(int userId) {
                        }
            });
        } catch (RemoteException e) {
            return false;
        }

        return res == ActivityManager.USER_OP_SUCCESS;
    }

    void finishRemoveUser(final int userHandle) {
        if (DBG) Slog.i(LOG_TAG, "finishRemoveUser " + userHandle);
        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        long ident = Binder.clearCallingIdentity();
        try {
            Intent addedIntent = new Intent(Intent.ACTION_USER_REMOVED);
            addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
            mContext.sendOrderedBroadcastAsUser(addedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS,

                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (DBG) {
                                Slog.i(LOG_TAG,
                                        "USER_REMOVED broadcast sent, cleaning up user data "
                                        + userHandle);
                            }
                            new Thread() {
                                public void run() {
                                    synchronized (mInstallLock) {
                                        synchronized (mPackagesLock) {
                                            removeUserStateLocked(userHandle);
                                        }
                                    }
                                }
                            }.start();
                        }
                    },

                    null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserStateLocked(final int userHandle) {
        // Cleanup package manager settings
        mPm.cleanUpUserLILPw(userHandle);

        // Remove this user from the list
        mUsers.remove(userHandle);

        // Have user ID linger for several seconds to let external storage VFS
        // cache entries expire. This must be greater than the 'entry_valid'
        // timeout used by the FUSE daemon.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (mPackagesLock) {
                    mRemovingUserIds.delete(userHandle);
                }
            }
        }, MINUTE_IN_MILLIS);

        // Remove user file
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userHandle + ".xml"));
        userFile.delete();
        // Update the user list
        writeUserListLocked();
        updateUserIdsLocked();
        removeDirectoryRecursive(Environment.getUserSystemDirectory(userHandle));
    }

    private void removeDirectoryRecursive(File parent) {
        if (parent.isDirectory()) {
            String[] files = parent.list();
            for (String filename : files) {
                File child = new File(parent, filename);
                removeDirectoryRecursive(child);
            }
        }
        parent.delete();
    }

    @Override
    public List<RestrictionEntry> getApplicationRestrictions(String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId
                || Binder.getCallingUid() != getUidForPackage(packageName)) {
            checkManageUsersPermission("Only system can get restrictions for other users/apps");
        }
        synchronized (mPackagesLock) {
            // Read the restrictions from XML
            return readApplicationRestrictionsLocked(packageName, userId);
        }
    }

    @Override
    public void setApplicationRestrictions(String packageName, List<RestrictionEntry> entries,
            int userId) {
        if (UserHandle.getCallingUserId() != userId
                || Binder.getCallingUid() != getUidForPackage(packageName)) {
            checkManageUsersPermission("Only system can set restrictions for other users/apps");
        }
        synchronized (mPackagesLock) {
            // Write the restrictions to XML
            writeApplicationRestrictionsLocked(packageName, entries, userId);
        }
    }

    private int getUidForPackage(String packageName) {
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES).uid;
        } catch (NameNotFoundException nnfe) {
            return -1;
        }
    }

    private List<RestrictionEntry> readApplicationRestrictionsLocked(String packageName,
            int userId) {
        final ArrayList<RestrictionEntry> entries = new ArrayList<RestrictionEntry>();
        final ArrayList<String> values = new ArrayList<String>();

        FileInputStream fis = null;
        try {
            AtomicFile restrictionsFile =
                    new AtomicFile(new File(Environment.getUserSystemDirectory(userId),
                            RESTRICTIONS_FILE_PREFIX + packageName + ".xml"));
            fis = restrictionsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read restrictions file "
                        + restrictionsFile.getBaseFile());
                return entries;
            }

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_ENTRY)) {
                    String key = parser.getAttributeValue(null, ATTR_KEY);
                    String multiple = parser.getAttributeValue(null, ATTR_MULTIPLE);
                    if (multiple != null) {
                        int count = Integer.parseInt(multiple);
                        while (count > 0 && (type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                            if (type == XmlPullParser.START_TAG
                                    && parser.getName().equals(TAG_VALUE)) {
                                values.add(parser.nextText().trim());
                                count--;
                            }
                        }
                        String [] valueStrings = new String[values.size()];
                        values.toArray(valueStrings);
                        Slog.d(LOG_TAG, "Got RestrictionEntry " + key + "," + valueStrings);
                        RestrictionEntry entry = new RestrictionEntry(key, valueStrings);
                        entries.add(entry);
                    } else {
                        String value = parser.nextText().trim();
                        Slog.d(LOG_TAG, "Got RestrictionEntry " + key + "," + value);
                        RestrictionEntry entry = new RestrictionEntry(key, value);
                        entries.add(entry);
                    }
                }
            }

        } catch (IOException ioe) {
        } catch (XmlPullParserException pe) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        return entries;
    }

    private void writeApplicationRestrictionsLocked(String packageName,
            List<RestrictionEntry> entries, int userId) {
        FileOutputStream fos = null;
        AtomicFile restrictionsFile = new AtomicFile(
                new File(Environment.getUserSystemDirectory(userId),
                        RESTRICTIONS_FILE_PREFIX + packageName + ".xml"));
        try {
            fos = restrictionsFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_RESTRICTIONS);

            for (RestrictionEntry entry : entries) {
                serializer.startTag(null, TAG_ENTRY);
                serializer.attribute(null, ATTR_KEY, entry.key);
                if (entry.getStringValue() != null || entry.getMultipleValues() == null) {
                    String value = entry.getStringValue();
                    serializer.text(value != null ? value : "");
                } else {
                    String[] values = entry.getMultipleValues();
                    serializer.attribute(null, ATTR_MULTIPLE, Integer.toString(values.length));
                    for (String value : values) {
                        serializer.startTag(null, TAG_VALUE);
                        serializer.text(value != null ? value : "");
                        serializer.endTag(null, TAG_VALUE);
                    }
                }
                serializer.endTag(null, TAG_ENTRY);
            }

            serializer.endTag(null, TAG_RESTRICTIONS);

            serializer.endDocument();
            restrictionsFile.finishWrite(fos);
        } catch (Exception e) {
            restrictionsFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing application restrictions list");
        }
    }

    @Override
    public int getUserSerialNumber(int userHandle) {
        synchronized (mPackagesLock) {
            if (!exists(userHandle)) return -1;
            return getUserInfoLocked(userHandle).serialNumber;
        }
    }

    @Override
    public int getUserHandle(int userSerialNumber) {
        synchronized (mPackagesLock) {
            for (int userId : mUserIds) {
                if (getUserInfoLocked(userId).serialNumber == userSerialNumber) return userId;
            }
            // Not found
            return -1;
        }
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIdsLocked() {
        int num = 0;
        for (int i = 0; i < mUsers.size(); i++) {
            if (!mUsers.valueAt(i).partial) {
                num++;
            }
        }
        final int[] newUsers = new int[num];
        int n = 0;
        for (int i = 0; i < mUsers.size(); i++) {
            if (!mUsers.valueAt(i).partial) {
                newUsers[n++] = mUsers.keyAt(i);
            }
        }
        mUserIds = newUsers;
    }

    /**
     * Make a note of the last started time of a user.
     * @param userId the user that was just foregrounded
     */
    public void userForeground(int userId) {
        synchronized (mPackagesLock) {
            UserInfo user = mUsers.get(userId);
            long now = System.currentTimeMillis();
            if (user == null || user.partial) {
                Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
                return;
            }
            if (now > EPOCH_PLUS_30_YEARS) {
                user.lastLoggedInTime = now;
                writeUserLocked(user);
            }
        }
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     * TODO: May not be a good idea to recycle ids, in case it results in confusion
     * for data and battery stats collection, or unexpected cross-talk.
     * @return
     */
    private int getNextAvailableIdLocked() {
        synchronized (mPackagesLock) {
            int i = MIN_USER_ID;
            while (i < Integer.MAX_VALUE) {
                if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.get(i)) {
                    break;
                }
                i++;
            }
            return i;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UserManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        synchronized (mPackagesLock) {
            pw.println("Users:");
            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo user = mUsers.valueAt(i);
                if (user == null) continue;
                pw.print("  "); pw.print(user); pw.print(" serialNo="); pw.print(user.serialNumber);
                if (mRemovingUserIds.get(mUsers.keyAt(i))) pw.print(" <removing> ");
                if (user.partial) pw.print(" <partial>");
                pw.println();
                pw.print("    Created: ");
                if (user.creationTime == 0) {
                    pw.println("<unknown>");
                } else {
                    sb.setLength(0);
                    TimeUtils.formatDuration(now - user.creationTime, sb);
                    sb.append(" ago");
                    pw.println(sb);
                }
                pw.print("    Last logged in: ");
                if (user.lastLoggedInTime == 0) {
                    pw.println("<unknown>");
                } else {
                    sb.setLength(0);
                    TimeUtils.formatDuration(now - user.lastLoggedInTime, sb);
                    sb.append(" ago");
                    pw.println(sb);
                }
            }
        }
    }
}
