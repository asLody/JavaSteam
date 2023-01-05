package in.dragonbra.javasteam.rpc.service;

import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient;
import in.dragonbra.javasteam.rpc.IChatRoomClient;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService;

// TODO implement

@SuppressWarnings("unused")
public class ChatRoomClient extends UnifiedService implements IChatRoomClient {

    public ChatRoomClient(SteamUnifiedMessages steamUnifiedMessages) {
        super(steamUnifiedMessages);
    }

    @Override
    public void NotifyIncomingChatMessage(SteammessagesChatSteamclient.CChatRoom_IncomingChatMessage_Notification request) {

    }

    @Override
    public void NotifyChatMessageModified(SteammessagesChatSteamclient.CChatRoom_ChatMessageModified_Notification request) {

    }

    @Override
    public void NotifyMemberStateChange(SteammessagesChatSteamclient.CChatRoom_MemberStateChange_Notification request) {

    }

    @Override
    public void NotifyChatRoomHeaderStateChange(SteammessagesChatSteamclient.CChatRoom_ChatRoomHeaderState_Notification request) {

    }

    @Override
    public void NotifyChatRoomGroupRoomsChange(SteammessagesChatSteamclient.CChatRoom_ChatRoomGroupRoomsChange_Notification request) {

    }

    @Override
    public void NotifyShouldRejoinChatRoomVoiceChat(SteammessagesChatSteamclient.CChatRoom_NotifyShouldRejoinChatRoomVoiceChat_Notification request) {

    }

    @Override
    public void NotifyChatGroupUserStateChanged(SteammessagesChatSteamclient.ChatRoomClient_NotifyChatGroupUserStateChanged_Notification request) {

    }

    @Override
    public void NotifyAckChatMessageEcho(SteammessagesChatSteamclient.CChatRoom_AckChatMessage_Notification request) {

    }

    @Override
    public void NotifyChatRoomDisconnect(SteammessagesChatSteamclient.ChatRoomClient_NotifyChatRoomDisconnect_Notification request) {

    }

    @Override
    public void NotifyMemberListViewUpdated(SteammessagesChatSteamclient.CChatRoomClient_MemberListViewUpdated_Notification request) {

    }

    @Override
    public void NotifyMessageReaction(SteammessagesChatSteamclient.CChatRoom_MessageReaction_Notification request) {

    }
}
