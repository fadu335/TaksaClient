package wtf.taksa.mixin.accessor;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.resource.ResourceReloadLogger;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.AbuseReportContext;
import net.minecraft.client.texture.PlayerSkinProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.net.Proxy;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("currentFps")
    static int getFps() {
        return 0;
    }

    @Mutable
    @Accessor("session")
    void setSession(Session session);

    @Accessor("itemUseCooldown")
    int getItemUseCooldown();

    @Accessor("itemUseCooldown")
    void setItemUseCooldown(int val);

    @Accessor("networkProxy")
    Proxy getProxy();

    @Accessor("resourceReloadLogger")
    ResourceReloadLogger getResourceReloadLogger();

    @Invoker("doAttack")
    boolean leftClick();

    @Mutable
    @Accessor("profileKeys")
    void setProfileKeys(ProfileKeys keys);

    @Invoker("doItemUse")
    void idoItemUse();

    @Accessor("authenticationService")
    YggdrasilAuthenticationService getAuthenticationService();

    @Mutable
    @Accessor
    void setUserApiService(UserApiService apiService);

    @Mutable
    @Accessor("sessionService")
    void setSessionService(MinecraftSessionService sessionService);

    @Mutable
    @Accessor("authenticationService")
    void setAuthenticationService(YggdrasilAuthenticationService authenticationService);

    @Mutable
    @Accessor("skinProvider")
    void setSkinProvider(PlayerSkinProvider skinProvider);

    @Mutable
    @Accessor("socialInteractionsManager")
    void setSocialInteractionsManager(SocialInteractionsManager socialInteractionsManager);

    @Mutable
    @Accessor("abuseReportContext")
    void setAbuseReportContext(AbuseReportContext abuseReportContext);
}
