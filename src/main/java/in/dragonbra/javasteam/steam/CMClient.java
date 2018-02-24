package in.dragonbra.javasteam.steam;

import in.dragonbra.javasteam.base.*;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.enums.EServerType;
import in.dragonbra.javasteam.enums.EUniverse;
import in.dragonbra.javasteam.networking.steam3.*;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesBase.CMsgMulti;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientCMList;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientServerList;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientSessionToken;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientHeartBeat;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLoggedOff;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLogonResponse;
import in.dragonbra.javasteam.steam.discovery.ServerQuality;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.types.SteamID;
import in.dragonbra.javasteam.util.IDebugNetworkListener;
import in.dragonbra.javasteam.util.MsgUtil;
import in.dragonbra.javasteam.util.NetHelpers;
import in.dragonbra.javasteam.util.event.EventArgs;
import in.dragonbra.javasteam.util.event.EventHandler;
import in.dragonbra.javasteam.util.event.ScheduledFunction;
import in.dragonbra.javasteam.util.stream.BinaryReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * This base client handles the underlying connection to a CM server. This class should not be use directly, but through
 * the {@link in.dragonbra.javasteam.steam.steamclient.SteamClient SteamClient} class.
 */
public abstract class CMClient {

    private static final Logger logger = LogManager.getLogger(CMClient.class);

    private SteamConfiguration configuration;

    private boolean isConnected;

    private long sessionToken;

    private Integer cellID;

    private Integer sessionID;

    private SteamID steamID;

    private IDebugNetworkListener debugNetworkListener;

    private boolean expectDisconnection;

    // connection lock around the setup and tear down of the connection task
    private final Object connectionLock = new Object();

    private Object connectionSetupTask;

    private Connection connection;

    private ScheduledFunction heartBeatFunc;

    private Map<EServerType, Set<InetSocketAddress>> serverMap;

    private final EventHandler<NetMsgEventArgs> netMsgReceived = (sender, e) -> onClientMsgReceived(getPacketMsg(e.getData()));

    private final EventHandler<EventArgs> connected = new EventHandler<EventArgs>() {
        @Override
        public void handleEvent(Object sender, EventArgs e) {
            getServers().tryMark(connection.getCurrentEndPoint(), connection.getProtocolTypes(), ServerQuality.GOOD);

            isConnected = true;
            onClientConnected();
        }
    };

    private final EventHandler<DisconnectedEventArgs> disconnected = new EventHandler<DisconnectedEventArgs>() {
        @Override
        public void handleEvent(Object sender, DisconnectedEventArgs e) {
            isConnected = false;

            if (e.isUserInitiated() && expectDisconnection) {
                getServers().tryMark(connection.getCurrentEndPoint(), connection.getProtocolTypes(), ServerQuality.BAD);
            }

            sessionID = null;
            steamID = null;

            connection.getNetMsgReceived().removeEventHandler(netMsgReceived);
            connection.getConnected().removeEventHandler(connected);
            connection.getDisconnected().removeEventHandler(this);
            connection = null;

            heartBeatFunc.stop();

            onClientDisconnected(e.isUserInitiated() || expectDisconnection);
        }
    };

    public CMClient(SteamConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is null");
        }

        this.configuration = configuration;
        this.serverMap = new HashMap<>();

        heartBeatFunc = new ScheduledFunction(() -> {
            send(new ClientMsgProtobuf<CMsgClientHeartBeat.Builder>(CMsgClientHeartBeat.class, EMsg.Heartbeat));
        }, 5000);
    }

    /**
     * Connects this client to a Steam3 server. This begins the process of connecting and encrypting the data channel
     * between the client and the server. Results are returned asynchronously in a {@link in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback ConnectedCallback}. If the
     * server that SteamKit attempts to connect to is down, a {@link in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback DisconnectedCallback} will be posted instead.
     * SteamKit will not attempt to reconnect to Steam, you must handle this callback and call Connect again preferably
     * after a short delay. SteamKit will randomly select a CM server from its internal list.
     */
    public void connect() {
        connect(null);
    }

    /**
     * Connects this client to a Steam3 server. This begins the process of connecting and encrypting the data channel
     * between the client and the server. Results are returned asynchronously in a {@link in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback ConnectedCallback}. If the
     * server that SteamKit attempts to connect to is down, a {@link in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback DisconnectedCallback} will be posted instead.
     * SteamKit will not attempt to reconnect to Steam, you must handle this callback and call Connect again preferably
     * after a short delay.
     *
     * @param cmServer The {@link ServerRecord} of the CM server to connect to.
     */
    public void connect(ServerRecord cmServer) {
        synchronized (connectionLock) {
            try {
                disconnect();

                assert connection != null;

                expectDisconnection = false;

                if (cmServer == null) {
                    cmServer = getServers().getNextServerCandidate(configuration.getProtocolTypes());
                }

                connection = createConnection(configuration.getProtocolTypes());
                connection.getNetMsgReceived().addEventHandler(netMsgReceived);
                connection.getConnected().addEventHandler(connected);
                connection.getDisconnected().addEventHandler(disconnected);
                connection.connect(cmServer.getEndpoint());
            } catch (Exception e) {
                onClientDisconnected(false);
            }
        }
    }

    /**
     * Disconnects this client.
     */
    public void disconnect() {
        synchronized (connectionLock) {
            heartBeatFunc.stop();

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Sends the specified client message to the server. This method automatically assigns the correct SessionID and
     * SteamID of the message.
     *
     * @param msg The client message to send.
     */
    public void send(IClientMsg msg) {
        if (msg == null) {
            throw new IllegalArgumentException("A value for 'msg' must be supplied");
        }

        if (sessionID != null) {
            msg.setSessionID(sessionID);
        }

        logger.debug(String.format("Sent -> EMsg: %s (Proto: %s)", msg.getMsgType(), msg.isProto()));

        try {
            if (debugNetworkListener != null) {
                debugNetworkListener.onOutgoingNetworkMessage(msg.getMsgType(), msg.serialize());
            }
        } catch (Exception e) {
            logger.debug("DebugNetworkListener threw an exception", e);
        }

        // we'll swallow any network failures here because they will be thrown later
        // on the network thread, and that will lead to a disconnect callback
        // down the line

        try {
            if (connection != null) {
                connection.send(msg.serialize());
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns the list of servers matching the given type
     *
     * @param type Server type requested
     * @return List of server endpoints
     */
    public List<InetSocketAddress> getServers(EServerType type) {
        if (serverMap.containsKey(type)) {
            return new ArrayList<>(serverMap.get(type));
        }

        return new ArrayList<>();
    }

    protected boolean onClientMsgReceived(IPacketMsg packetMsg) {
        if (packetMsg == null) {
            logger.debug("Packet message failed to parse, shutting down connection");
            disconnect();
            return false;
        }

        logger.debug(String.format("<- Recv'd EMsg: %s (%d) (Proto: %s)", packetMsg.getMsgType(), packetMsg.getMsgType().code(), packetMsg.isProto()));

        // Multi message gets logged down the line after it's decompressed
        if (packetMsg.getMsgType() != EMsg.Multi) {
            try {
                if (debugNetworkListener != null) {
                    debugNetworkListener.onIncomingNetworkMessage(packetMsg.getMsgType(), packetMsg.getData());
                }
            } catch (Exception e) {
                logger.debug("debugNetworkListener threw an exception", e);
            }
        }

        switch (packetMsg.getMsgType()) {
            case Multi:
                handleMulti(packetMsg);
                break;
            case ClientLogOnResponse: // we handle this to get the SteamID/SessionID and to setup heartbeating
                handleLogOnResponse(packetMsg);
                break;
            case ClientLoggedOff: // to stop heartbeating when we get logged off
                handleLoggedOff(packetMsg);
                break;
            case ClientServerList: // Steam server list
                handleServerList(packetMsg);
                break;
            case ClientCMList:
                handleCMList(packetMsg);
                break;
            case ClientSessionToken: // am session token
                handleSessionToken(packetMsg);
                break;
        }

        return true;
    }

    /**
     * Called when the client is securely isConnected to Steam3.
     */
    protected void onClientConnected() {

    }

    /**
     * Called when the client is physically disconnected from Steam3.
     */
    protected void onClientDisconnected(boolean userInitiated) {
        serverMap.values().forEach(Set::clear);
    }

    private Connection createConnection(ProtocolTypes protocol) {
        if ((protocol.code() & ProtocolTypes.WEB_SOCKET.code()) > 0) {
            // TODO: 2018-02-21  
            //return new WebSocketConnection();
        } else if ((protocol.code() & ProtocolTypes.TCP.code()) > 0) {
            return new EnvelopeEncryptedConnection(new TcpConnection(), getUniverse());
        } else if ((protocol.code() & ProtocolTypes.UDP.code()) > 0) {
            // TODO: 2018-02-21  
            //return new EnvelopeEncryptedConnection(new UdpConnection(), getUniverse());
        }

        throw new IllegalArgumentException("Protocol bitmask has no supported protocols set.");
    }

    public static IPacketMsg getPacketMsg(byte[] data) {
        if (data.length < 4) {
            logger.debug("PacketMsg too small to contain a message, was only {0} bytes. Message: 0x{1}");
            return null;
        }

        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data));

        int rawEMsg = 0;
        try {
            rawEMsg = reader.readInt();
        } catch (IOException e) {
            logger.debug("Exception while getting EMsg code", e);
        }
        EMsg eMsg = MsgUtil.getMsg(rawEMsg);

        switch (eMsg) {
            case ChannelEncryptRequest:
            case ChannelEncryptResponse:
            case ChannelEncryptResult:
                try {
                    return new PacketMsg(eMsg, data);
                } catch (IOException e) {
                    logger.debug("Exception deserializing emsg " + eMsg + " (" + MsgUtil.isProtoBuf(rawEMsg) + ").", e);
                }
        }

        try {
            if (MsgUtil.isProtoBuf(rawEMsg)) {
                return new PacketClientMsgProtobuf(eMsg, data);
            } else {
                return new PacketClientMsg(eMsg, data);
            }
        } catch (IOException e) {
            logger.debug("Exception deserializing emsg " + eMsg + " (" + MsgUtil.isProtoBuf(rawEMsg) + ").", e);
            return null;
        }
    }

    private void handleMulti(IPacketMsg packetMsg) {
        if (!packetMsg.isProto()) {
            logger.debug("HandleMulti got non-proto MsgMulti!!");
            return;
        }

        ClientMsgProtobuf<CMsgMulti.Builder> msgMulti = new ClientMsgProtobuf<>(CMsgMulti.class, packetMsg);

        byte[] payload = msgMulti.getBody().getMessageBody().toByteArray();

        if (msgMulti.getBody().getSizeUnzipped() > 0) {
            try {
                GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(payload));
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                int res = 0;
                byte buf[] = new byte[1024];
                while (res >= 0) {
                    res = gzin.read(buf, 0, buf.length);
                    if (res > 0) {
                        baos.write(buf, 0, res);
                    }
                }
                payload = baos.toByteArray();
            } catch (IOException e) {
                logger.debug("HandleMulti encountered an exception when decompressing.", e);
                return;
            }
        }

        try (BinaryReader br = new BinaryReader(new ByteArrayInputStream(payload))) {
            while (br.available() > 0) {
                int subSize = br.readInt();
                byte[] subData = br.readBytes(subSize);

                if (!onClientMsgReceived(getPacketMsg(subData))) {
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleLogOnResponse(IPacketMsg packetMsg) {
        if (!packetMsg.isProto()) {
            // a non proto ClientLogonResponse can come in as a result of connecting but never sending a ClientLogon
            // in this case, it always fails, so we don't need to do anything special here
            logger.debug("Got non-proto logon response, this is indicative of no logon attempt after connecting.");
            return;
        }

        ClientMsgProtobuf<CMsgClientLogonResponse.Builder> logonResp = new ClientMsgProtobuf<>(CMsgClientLogonResponse.class, packetMsg);

        if (logonResp.getBody().getEresult() == EResult.OK.code()) {
            sessionID = logonResp.getProtoHeader().getClientSessionid();
            steamID = new SteamID(logonResp.getProtoHeader().getSteamid());

            cellID = logonResp.getBody().getCellId();

            // restart heartbeat
            heartBeatFunc.stop();
            heartBeatFunc.setDelay(logonResp.getBody().getOutOfGameHeartbeatSeconds() + 1000);
            heartBeatFunc.start();
        }
    }

    private void handleLoggedOff(IPacketMsg packetMsg) {
        sessionID = null;
        steamID = null;

        cellID = null;

        heartBeatFunc.stop();

        if (packetMsg.isProto()) {
            ClientMsgProtobuf<CMsgClientLoggedOff.Builder> logoffMsg = new ClientMsgProtobuf<>(CMsgClientLoggedOff.class, packetMsg);
            EResult logoffResult = EResult.from(logoffMsg.getBody().getEresult());

            if (logoffResult == EResult.TryAnotherCM || logoffResult == EResult.ServiceUnavailable) {
                getServers().tryMark(connection.getCurrentEndPoint(), connection.getProtocolTypes(), ServerQuality.BAD);
            }
        }
    }

    private void handleServerList(IPacketMsg packetMsg) {
        ClientMsgProtobuf<CMsgClientServerList.Builder> listMsg = new ClientMsgProtobuf<>(CMsgClientServerList.class, packetMsg);

        for (CMsgClientServerList.Server server : listMsg.getBody().getServersList()) {
            EServerType type = EServerType.from(server.getServerType());

            Set<InetSocketAddress> endPointSet;
            if (!serverMap.containsKey(type)) {
                endPointSet = new HashSet<>();
                serverMap.put(type, endPointSet);
            } else {
                endPointSet = serverMap.get(type);
            }

            endPointSet.add(new InetSocketAddress(NetHelpers.getIPAddress(server.getServerIp()), server.getServerPort()));
        }
    }

    private void handleCMList(IPacketMsg packetMsg) {
        ClientMsgProtobuf<CMsgClientCMList.Builder> cmMsg = new ClientMsgProtobuf<>(CMsgClientCMList.class, packetMsg);

        if (cmMsg.getBody().getCmPortsCount() != cmMsg.getBody().getCmAddressesCount()) {
            logger.debug("HandleCMList received malformed message");
        }

        List<Integer> addresses = cmMsg.getBody().getCmAddressesList();
        List<Integer> ports = cmMsg.getBody().getCmPortsList();
        List<ServerRecord> cmList = IntStream.range(0, Math.min(addresses.size(), ports.size()))
                .mapToObj(i -> ServerRecord.createSocketServer(new InetSocketAddress(NetHelpers.getIPAddress(addresses.get(i)), ports.get(i))))
                .collect(Collectors.toList());

        List<ServerRecord> webSocketList = cmMsg.getBody().getCmWebsocketAddressesList().stream()
                .map(ServerRecord::createWebSocketServer)
                .collect(Collectors.toList());

        cmList.addAll(webSocketList);
        getServers().replaceList(cmList);
    }

    private void handleSessionToken(IPacketMsg packetMsg) {
        ClientMsgProtobuf<CMsgClientSessionToken.Builder> sessToken = new ClientMsgProtobuf<>(CMsgClientSessionToken.class, packetMsg);

        sessionToken = sessToken.getBody().getToken();
    }

    public SteamConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * @return Bootstrap list of CM servers.
     */
    public SmartCMServerList getServers() {
        return configuration.getServerList();
    }

    /**
     * Returns the the local IP of this client.
     *
     * @return The local IP.
     */
    public InetAddress getLocalIP() {
        return connection.getLocalIP();
    }

    /**
     * Gets the universe of this client.
     *
     * @return The universe.
     */
    public EUniverse getUniverse() {
        return configuration.getUniverse();
    }

    /**
     * Gets a value indicating whether this instance is isConnected to the remote CM server.
     *
     * @return <c>true</c> if this instance is isConnected; otherwise, <c>false</c>.
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Gets the session token assigned to this client from the AM.
     */
    public long getSessionToken() {
        return sessionToken;
    }

    /**
     * Gets the Steam recommended Cell ID of this client. This value is assigned after a logon attempt has succeeded.
     * This value will be <c>null</c> if the client is logged off of Steam.
     */
    public Integer getCellID() {
        return cellID;
    }

    /**
     * Gets the session ID of this client. This value is assigned after a logon attempt has succeeded.
     * This value will be <c>null</c> if the client is logged off of Steam.
     *
     * @return The session ID.
     */
    public Integer getSessionID() {
        return sessionID;
    }

    /**
     * Gets the SteamID of this client. This value is assigned after a logon attempt has succeeded.
     * This value will be <c>null</c> if the client is logged off of Steam.
     *
     * @return The SteamID.
     */
    public SteamID getSteamID() {
        return steamID;
    }

    /**
     * Gets or sets the connection timeout used when connecting to the Steam server.
     *
     * @return The connection timeout.
     */
    public long getConnectionTimeout() {
        return configuration.getConnectionTimeout();
    }

    /**
     * Gets the network listening interface. Use this for debugging only.
     * For your convenience, you can use {@link NetHookNetworkListener} class.
     */
    public IDebugNetworkListener getDebugNetworkListener() {
        return debugNetworkListener;
    }

    /**
     * Sets the network listening interface. Use this for debugging only.
     * For your convenience, you can use {@link NetHookNetworkListener} class.
     */
    public void setDebugNetworkListener(IDebugNetworkListener debugNetworkListener) {
        this.debugNetworkListener = debugNetworkListener;
    }

    public boolean isExpectDisconnection() {
        return expectDisconnection;
    }

    public void setExpectDisconnection(boolean expectDisconnection) {
        this.expectDisconnection = expectDisconnection;
    }
}