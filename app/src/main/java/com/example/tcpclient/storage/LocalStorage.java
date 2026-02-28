package com.example.tcpclient.storage;

import java.util.ArrayList;
import java.util.List;

import chat.models.GroupChat;


public class LocalStorage {
    public static List<GroupChat> currentUserGroupChats = new ArrayList<>();
    public static String pendingSecretKey = null;
    public static List<GroupChat> getCurrentUserGroupChats() {
        return currentUserGroupChats;
    }
    public static void setCurrentUserGroupChats(List<GroupChat> currentUserGroupChats) {
        LocalStorage.currentUserGroupChats = currentUserGroupChats;
    }
}
