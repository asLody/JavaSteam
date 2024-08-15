package `in`.dragonbra.javasteam.steam.handlers.steamgameserver

import com.google.protobuf.ByteString
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EAccountType
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EServerFlags
import `in`.dragonbra.javasteam.generated.MsgClientLogon
import `in`.dragonbra.javasteam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverGameservers.CMsgGSServerType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLogOff
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLogon
import `in`.dragonbra.javasteam.steam.handlers.steamgameserver.callback.StatusReplyCallback
import `in`.dragonbra.javasteam.steam.handlers.steamgameserver.callback.TicketAuthCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.HardwareUtils
import `in`.dragonbra.javasteam.util.NetHelpers
import `in`.dragonbra.javasteam.util.Utils
import java.net.Inet6Address

/**
 * This handler is used for interacting with the Steam network as a game server.
 */
@Suppress("unused")
class SteamGameServer : ClientMsgHandler() {

    /**
     * Logs onto the Steam network as a persistent game server.
     * The client should already have been connected at this point.
     * Results are return in a [LoggedOnCallback].
     *
     * @param details The details to use for logging on.
     */
    fun logOn(details: LogOnDetails) {
        require(!details.token.isNullOrEmpty()) { "LogOn requires a game server token to be set in 'details'." }

        if (!client.isConnected) {
            LoggedOnCallback(EResult.NoConnection).also(client::postCallback)
            return
        }

        val logon = ClientMsgProtobuf<CMsgClientLogon.Builder>(CMsgClientLogon::class.java, EMsg.ClientLogonGameServer)

        val gsId = SteamID(0, 0, client.universe, EAccountType.GameServer)

        logon.protoHeader.setClientSessionid(0)
        logon.protoHeader.setSteamid(gsId.convertToUInt64())

        val localIp: Int = NetHelpers.getIPAddress(client.localIP)
        logon.body.setDeprecatedObfustucatedPrivateIp(localIp xor MsgClientLogon.ObfuscationMask) // TODO: Using deprecated method.

        logon.body.setProtocolVersion(MsgClientLogon.CurrentProtocol)

        logon.body.setClientOsType(Utils.getOSType().code())
        logon.body.setGameServerAppId(details.appID)
        logon.body.setMachineId(ByteString.copyFrom(HardwareUtils.getMachineID()))

        logon.body.setGameServerToken(details.token)

        client.send(logon)
    }

    /**
     * Logs the client into the Steam3 network as an anonymous game server.
     * The client should already have been connected at this point.
     * Results are return in a [LoggedOnCallback].
     *
     * @param appId The AppID served by this game server, or 0 for the default.
     */
    @JvmOverloads
    fun logOnAnonymous(appId: Int = 0) {
        if (!client.isConnected) {
            client.postCallback(LoggedOnCallback(EResult.NoConnection))
            return
        }

        val logon = ClientMsgProtobuf<CMsgClientLogon.Builder>(CMsgClientLogon::class.java, EMsg.ClientLogonGameServer)

        val gsId = SteamID(0, 0, client.universe, EAccountType.AnonGameServer)

        logon.protoHeader.setClientSessionid(0)
        logon.protoHeader.setSteamid(gsId.convertToUInt64())

        val localIp: Int = NetHelpers.getIPAddress(client.localIP)
        logon.body.setDeprecatedObfustucatedPrivateIp(localIp xor MsgClientLogon.ObfuscationMask) // TODO: Using deprecated method.

        logon.body.setProtocolVersion(MsgClientLogon.CurrentProtocol)

        logon.body.setClientOsType(Utils.getOSType().code())
        logon.body.setGameServerAppId(appId)
        logon.body.setMachineId(ByteString.copyFrom(HardwareUtils.getMachineID()))

        client.send(logon)
    }

    /**
     * Informs the Steam servers that this client wishes to log off from the network.
     * The Steam server will disconnect the client, and a [DisconnectedCallback] will be posted.
     */
    fun logOff() {
        isExpectDisconnection = true

        val logOff = ClientMsgProtobuf<CMsgClientLogOff.Builder>(CMsgClientLogOff::class.java, EMsg.ClientLogOff)
        client.send(logOff)

        // TODO: 2018-02-28 it seems like the socket is not closed after getting logged of or I am doing something horribly wrong, let's disconnect here
        client.disconnect()
    }

    /**
     * Sends the server's status to the Steam network.
     * Results are returned in a [StatusReplyCallback] callback.
     * @param details A [StatusDetails] object containing the server's status.
     */
    fun sendStatus(details: StatusDetails) {
        require(!(details.address != null && details.address is Inet6Address)) { "Only IPv4 addresses are supported." }

        val status = ClientMsgProtobuf<CMsgGSServerType.Builder>(CMsgGSServerType::class.java, EMsg.GSServerType)
        status.body.setAppIdServed(details.appID)
        status.body.setFlags(EServerFlags.code(details.serverFlags))
        status.body.setGameDir(details.gameDirectory)
        status.body.setGamePort(details.port)
        status.body.setGameQueryPort(details.queryPort)
        status.body.setGameVersion(details.version)

        details.address?.let {
            status.body.setDeprecatedGameIpAddress(NetHelpers.getIPAddress(it)) // TODO: Using deprecated method.
        }

        client.send(status)
    }

    /**
     * Handles a client message. This should not be called directly.
     * @param packetMsg The packet message that contains the data.
     */
    override fun handleMsg(packetMsg: IPacketMsg) {
        // ignore messages that we don't have a handler function for
        val callback = getCallback(packetMsg) ?: return

        client.postCallback(callback)
    }

    companion object {
        private fun getCallback(packetMsg: IPacketMsg): CallbackMsg? = when (packetMsg.msgType) {
            EMsg.GSStatusReply -> StatusReplyCallback(packetMsg)
            EMsg.ClientTicketAuthComplete -> TicketAuthCallback(packetMsg)
            else -> null
        }
    }
}