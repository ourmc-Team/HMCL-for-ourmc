/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.ServerDisconnectException;
import org.jackhuang.hmcl.auth.ServerResponseMalformedException;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.UUIDTypeAdapter;
import org.jackhuang.hmcl.util.gson.ValidationTypeAdapterFactory;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.HttpMultipartRequest;
import org.jackhuang.hmcl.util.io.IOUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.javafx.ObservableOptionalCache;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Lang.threadPool;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.Pair.pair;

public class YggdrasilService {

    private static final ThreadPoolExecutor POOL = threadPool("YggdrasilProfileProperties", true, 2, 10, TimeUnit.SECONDS);

    private final YggdrasilProvider provider;
    private final ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> profileRepository;

    public YggdrasilService(YggdrasilProvider provider) {
        this.provider = provider;
        this.profileRepository = new ObservableOptionalCache<>(
                uuid -> {
                    LOG.info("Fetching properties of " + uuid + " from " + provider);
                    return getCompleteGameProfile(uuid);
                },
                (uuid, e) -> LOG.warning("Failed to fetch properties of " + uuid + " from " + provider, e),
                POOL);
    }

    public ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> getProfileRepository() {
        return profileRepository;
    }

    public YggdrasilSession authenticate(String username, String password, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        Objects.requireNonNull(clientToken);

        Map<String, Object> request = new HashMap<>();
        request.put("agent", mapOf(
                pair("name", "Minecraft"),
                pair("version", 1)
        ));
        request.put("username", username);
        request.put("password", password);
        request.put("clientToken", clientToken);
        request.put("requestUser", true);

        return handleAuthenticationResponse(request(provider.getAuthenticationURL(), request), clientToken);
    }

    private static Map<String, Object> createRequestWithCredentials(String accessToken, String clientToken) {
        Map<String, Object> request = new HashMap<>();
        request.put("accessToken", accessToken);
        request.put("clientToken", clientToken);
        return request;
    }

    public YggdrasilSession refresh(String accessToken, String clientToken, GameProfile characterToSelect) throws AuthenticationException {
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(clientToken);

        Map<String, Object> request = createRequestWithCredentials(accessToken, clientToken);
        request.put("requestUser", true);

        if (characterToSelect != null) {
            request.put("selectedProfile", mapOf(
                    pair("id", characterToSelect.getId()),
                    pair("name", characterToSelect.getName())));
        }

        YggdrasilSession response = handleAuthenticationResponse(request(provider.getRefreshmentURL(), request), clientToken);

        if (characterToSelect != null) {
            if (response.getSelectedProfile() == null ||
                    !response.getSelectedProfile().getId().equals(characterToSelect.getId())) {
                throw new ServerResponseMalformedException("Failed to select character");
            }
        }

        return response;
    }

    public boolean validate(String accessToken) throws AuthenticationException {
        return validate(accessToken, null);
    }

    public boolean validate(String accessToken, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        try {
            requireEmpty(request(provider.getValidationURL(), createRequestWithCredentials(accessToken, clientToken)));
            return true;
        } catch (RemoteAuthenticationException e) {
            if ("ForbiddenOperationException".equals(e.getRemoteName())) {
                return false;
            }
            throw e;
        }
    }

    public void invalidate(String accessToken) throws AuthenticationException {
        invalidate(accessToken, null);
    }

    public void invalidate(String accessToken, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        requireEmpty(request(provider.getInvalidationURL(), createRequestWithCredentials(accessToken, clientToken)));
    }

    public void uploadSkin(UUID uuid, String accessToken, boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        try {
            HttpURLConnection con = NetworkUtils.createHttpConnection(provider.getSkinUploadURL(uuid));
            con.setRequestMethod("PUT");
            con.setRequestProperty("Authorization", "Bearer " + accessToken);
            con.setDoOutput(true);
            try (HttpMultipartRequest request = new HttpMultipartRequest(con)) {
                request.param("model", isSlim ? "slim" : "");
                try (InputStream fis = Files.newInputStream(file)) {
                    request.file("file", FileUtils.getName(file), "image/" + FileUtils.getExtension(file), fis);
                }
            }
            requireEmpty(NetworkUtils.readFullyAsString(con));
        } catch (IOException e) {
            throw new AuthenticationException(e);
        }
    }

    /**
     * Get complete game profile.
     *
     * Game profile provided from authentication is not complete (no skin data in properties).
     *
     * @param uuid the uuid that the character corresponding to.
     * @return the complete game profile(filled with more properties)
     */
    public Optional<CompleteGameProfile> getCompleteGameProfile(UUID uuid) throws AuthenticationException {
        Objects.requireNonNull(uuid);

        return Optional.ofNullable(fromJson(request(provider.getProfilePropertiesURL(uuid), null), CompleteGameProfile.class));
    }

    public static Optional<Map<TextureType, Texture>> getTextures(CompleteGameProfile profile) throws ServerResponseMalformedException {
        Objects.requireNonNull(profile);

        String encodedTextures = profile.getProperties().get("textures");

        if (encodedTextures != null) {
            byte[] decodedBinary;
            try {
                decodedBinary = Base64.getDecoder().decode(encodedTextures);
            } catch (IllegalArgumentException e) {
                throw new ServerResponseMalformedException(e);
            }
            TextureResponse texturePayload = fromJson(new String(decodedBinary, UTF_8), TextureResponse.class);
            return Optional.ofNullable(texturePayload.textures);
        } else {
            return Optional.empty();
        }
    }

    private static YggdrasilSession handleAuthenticationResponse(String responseText, String clientToken) throws AuthenticationException {
        LOG.info("Handling authentication response...");
        
        AuthenticationResponse response;
        try {
            response = fromJson(responseText, AuthenticationResponse.class);
        } catch (ServerResponseMalformedException e) {
            LOG.warning("Failed to parse authentication response: " + responseText);
            throw e;
        }
        
        // Log the parsed response fields for debugging
        LOG.info("Parsed response - error: " + response.error + ", errorMessage: " + response.errorMessage);
        LOG.info("Parsed response - accessToken: " + (response.accessToken != null ? "[PRESENT]" : "[NULL]"));
        LOG.info("Parsed response - clientToken: " + (response.clientToken != null ? "[PRESENT]" : "[NULL]"));
        LOG.info("Parsed response - selectedProfile: " + (response.selectedProfile != null ? "[PRESENT]" : "[NULL]"));
        LOG.info("Parsed response - availableProfiles: " + (response.availableProfiles != null ? "[PRESENT, count=" + response.availableProfiles.size() + "]" : "[NULL]"));
        LOG.info("Parsed response - user: " + (response.user != null ? "[PRESENT]" : "[NULL]"));
        
        handleErrorMessage(response);

        if (response.clientToken == null) {
            LOG.warning("Missing clientToken in response");
            throw new ServerResponseMalformedException("Missing clientToken in response");
        }
        
        if (!clientToken.equals(response.clientToken)) {
            LOG.warning("Client token mismatch: expected " + clientToken + ", got " + response.clientToken);
            throw new AuthenticationException("Client token changed from " + clientToken + " to " + response.clientToken);
        }

        if (response.accessToken == null) {
            LOG.warning("Missing accessToken in response");
            throw new ServerResponseMalformedException("Missing accessToken in response");
        }

        if (response.selectedProfile == null) {
            LOG.warning("Missing selectedProfile in response - this may be acceptable for some servers");
        } else {
            LOG.info("Selected profile - id: " + response.selectedProfile.getId() + ", name: " + response.selectedProfile.getName());
        }

        return new YggdrasilSession(
                response.clientToken,
                response.accessToken,
                response.selectedProfile,
                response.availableProfiles == null ? null : unmodifiableList(response.availableProfiles),
                response.user == null ? null : response.user.getProperties());
    }

    private static void requireEmpty(String response) throws AuthenticationException {
        if (StringUtils.isBlank(response))
            return;

        handleErrorMessage(fromJson(response, ErrorResponse.class));
    }

    private static void handleErrorMessage(ErrorResponse response) throws AuthenticationException {
        if (!StringUtils.isBlank(response.error)) {
            throw new RemoteAuthenticationException(response.error, response.errorMessage, response.cause);
        }
    }

    private static String request(URI uri, Object payload) throws AuthenticationException {
        HttpURLConnection con = null;
        try {
            String result;
            if (payload == null) {
                LOG.info("Sending GET request to: " + uri);
                con = NetworkUtils.createHttpConnection(uri);
                con.setRequestMethod("GET");
                result = NetworkUtils.readFullyAsString(con);
            } else {
                String jsonPayload = payload instanceof String ? (String) payload : GSON.toJson(payload);
                LOG.info("Sending POST request to: " + uri);
                LOG.info("Request payload: " + jsonPayload);
                
                con = NetworkUtils.createHttpConnection(uri);
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                con.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (java.io.OutputStream os = con.getOutputStream()) {
                    os.write(bytes);
                }
                
                // Check HTTP status code
                int responseCode = con.getResponseCode();
                LOG.info("HTTP response code: " + responseCode);
                
                if (responseCode >= 400) {
                    LOG.warning("HTTP error response code: " + responseCode + " for " + uri);
                    // Try to read error message from error stream
                    String errorResponse;
                    try (InputStream stderr = con.getErrorStream()) {
                        if (stderr != null) {
                            errorResponse = IOUtils.readFullyAsString(stderr, java.nio.charset.StandardCharsets.UTF_8);
                            LOG.warning("Error response body: " + errorResponse);
                        } else {
                            errorResponse = "No error body";
                        }
                    }
                    throw new RemoteAuthenticationException(
                        "HTTP_" + responseCode,
                        "HTTP error: " + responseCode + " - " + errorResponse,
                        null
                    );
                }
                
                result = NetworkUtils.readFullyAsString(con);
            }
            LOG.info("Response from " + uri + ": " + (result != null ? result.substring(0, Math.min(500, result.length())) : "null"));
            return result;
        } catch (RemoteAuthenticationException e) {
            // Re-throw remote authentication exceptions as-is
            throw e;
        } catch (IOException e) {
            LOG.warning("Network error when requesting " + uri + ": " + e.getMessage(), e);
            throw new ServerDisconnectException(e);
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static <T> T fromJson(String text, Class<T> typeOfT) throws ServerResponseMalformedException {
        if (text == null || text.isEmpty()) {
            LOG.warning("Empty response received");
            throw new ServerResponseMalformedException("Empty response from server");
        }
        
        LOG.info("Parsing JSON response (length: " + text.length() + "): " + text.substring(0, Math.min(1000, text.length())));
        
        try {
            T result = GSON.fromJson(text, typeOfT);
            LOG.info("JSON parsing successful");
            return result;
        } catch (JsonParseException e) {
            LOG.warning("Failed to parse JSON response: " + text, e);
            LOG.warning("JSON parse error details: " + e.getMessage());
            throw new ServerResponseMalformedException(e);
        }
    }

    private final static class TextureResponse {
        public Map<TextureType, Texture> textures;
    }

    private final static class AuthenticationResponse extends ErrorResponse {
        @SerializedName("accessToken")
        public String accessToken;
        
        @SerializedName("clientToken")
        public String clientToken;
        
        @SerializedName("selectedProfile")
        public GameProfile selectedProfile;
        
        @SerializedName("availableProfiles")
        public List<GameProfile> availableProfiles;
        
        @SerializedName("user")
        public User user;
    }

    private static class ErrorResponse {
        public String error;
        public String errorMessage;
        public String cause;
    }

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, UUIDTypeAdapter.INSTANCE)
            .registerTypeAdapterFactory(ValidationTypeAdapterFactory.INSTANCE)
            .serializeNulls()  // Allow null values
            .create();

    public static final String PURCHASE_URL = "https://www.xbox.com/games/store/minecraft-java-bedrock-edition-for-pc/9nxp44l49shj";
}
