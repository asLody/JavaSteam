package in.dragonbra.javasteam.generated;

import in.dragonbra.javasteam.base.ISteamSerializableMessage;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EResult;

import java.io.InputStream;
import java.io.OutputStream;

public class MsgChannelEncryptResult implements ISteamSerializableMessage {

    private EResult result = EResult.Invalid;

    @Override
    public EMsg getEMsg() {
        return EMsg.ChannelEncryptResult;
    }

    public EResult getResult() {
        return this.result;
    }

    public void setResult(EResult result) {
        this.result = result;
    }

    @Override
    public void serialize(OutputStream stream) {
    }

    @Override
    public void deserialize(InputStream stream) {
    }
}
