package me.ampura16.getHead;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GetHeadMain extends JavaPlugin {

    private final String pluginPrefix = ChatColor.DARK_GREEN + "[GetHead] ";
    private final JSONParser jsonParser = new JSONParser();
    private final Map<String, UUID> nameToUUIDCache = new HashMap<>();
    private final Map<UUID, String> textureCache = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info(pluginPrefix + "GetHead插件已启用.");
        getCommand("gethead").setExecutor(this);
    }

    @Override
    public void onDisable() {
        getLogger().info(pluginPrefix + "GetHead插件已关闭.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("gethead")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(colorize("red", pluginPrefix + "只有玩家才能使用此命令."));
            return true;
        }

        Player player = (Player) sender;

        if (args.length != 1) {
            player.sendMessage(colorize("green", pluginPrefix + "用法: /gethead <正版玩家ID>"));
            return true;
        }

        String targetPlayerName = args[0];

        CompletableFuture.runAsync(() -> {
            try {
                // 获取玩家UUID
                UUID uuid = getUUIDFromName(targetPlayerName);
                if (uuid == null) {
                    throw new Exception("无法找到该玩家，请确认玩家ID是否正确且是正版账号");
                }

                // 获取皮肤纹理
                String texture = getTexture(uuid);
                if (texture == null) {
                    throw new Exception("无法获取该玩家的皮肤数据");
                }

                // 创建头颅
                ItemStack skull = createSkull(targetPlayerName, texture);

                // 回到主线程给予物品
                Bukkit.getScheduler().runTask(this, () -> {
                    player.getInventory().addItem(skull);
                    player.sendMessage(colorize("green", pluginPrefix + "已获取玩家 " + targetPlayerName + " 的头颅."));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage(colorize("red", pluginPrefix + translateErrorMessage(e)));
                    getLogger().warning(pluginPrefix + "获取玩家头颅时出错: " + e.getMessage());
                });
            }
        });

        return true;
    }

    private String translateErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg.contains("timed out")) return "请求超时，请稍后再试";
        if (msg.contains("429")) return "请求过于频繁，请稍后再试";
        if (msg.contains("404") || msg.contains("204")) return "找不到该玩家，请确认ID是否正确";
        if (msg.contains("无法获取")) return "无法获取该玩家的皮肤数据";
        return "获取头颅时出错: " + msg;
    }

    private UUID getUUIDFromName(String name) throws Exception {
        String lowerName = name.toLowerCase();
        if (nameToUUIDCache.containsKey(lowerName)) {
            return nameToUUIDCache.get(lowerName);
        }

        String url = "https://api.mojang.com/users/profiles/minecraft/" + name;
        String response = makeHttpRequest(url);

        if (response == null || response.isEmpty()) {
            throw new Exception("API返回空响应");
        }

        JSONObject json = (JSONObject) jsonParser.parse(response);
        if (json == null || !json.containsKey("id")) {
            throw new Exception("API响应中缺少ID字段");
        }

        String uuidStr = json.get("id").toString();
        UUID uuid = formatUUID(uuidStr);
        nameToUUIDCache.put(lowerName, uuid);
        return uuid;
    }

    private String getTexture(UUID uuid) throws Exception {
        if (textureCache.containsKey(uuid)) {
            return textureCache.get(uuid);
        }

        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "");
        String response = makeHttpRequest(url);

        if (response == null || response.isEmpty()) {
            throw new Exception("API返回空响应");
        }

        JSONObject json = (JSONObject) jsonParser.parse(response);
        if (json == null) {
            throw new Exception("API返回无效的JSON数据");
        }

        if (!json.containsKey("properties")) {
            throw new Exception("无法获取皮肤数据");
        }

        Object propertiesObj = json.get("properties");
        if (!(propertiesObj instanceof JSONArray)) {
            throw new Exception("properties字段格式不正确");
        }

        JSONArray properties = (JSONArray) propertiesObj;
        for (Object prop : properties) {
            if (!(prop instanceof JSONObject)) continue;

            JSONObject textureProperty = (JSONObject) prop;
            if (textureProperty.containsKey("name") &&
                    "textures".equals(textureProperty.get("name"))) {

                String value = textureProperty.get("value").toString();
                String decoded = new String(Base64.getDecoder().decode(value));
                JSONObject textureJson = (JSONObject) jsonParser.parse(decoded);
                JSONObject textures = (JSONObject) textureJson.get("textures");
                JSONObject skin = (JSONObject) textures.get("SKIN");
                String textureUrl = skin.get("url").toString();

                // 从URL中提取纹理哈希
                String texture = textureUrl.substring(
                        textureUrl.lastIndexOf("/") + 1,
                        textureUrl.length()
                );

                textureCache.put(uuid, texture);
                return texture;
            }
        }

        throw new Exception("无法获取皮肤纹理");
    }

    private ItemStack createSkull(String playerName, String texture) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();

        // 使用Paper API创建玩家资料
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), playerName);

        // 构建皮肤数据
        String skinValue = String.format(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/%s\"}}}",
                texture
        );
        String encoded = Base64.getEncoder().encodeToString(skinValue.getBytes());

        // 设置皮肤属性
        profile.setProperty(new ProfileProperty("textures", encoded));

        // 应用到头颅
        meta.setPlayerProfile(profile);
        skull.setItemMeta(meta);

        return skull;
    }

    private String makeHttpRequest(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode == 429) {
            throw new Exception("请求过于频繁，请稍后再试 (429)");
        }
        if (responseCode == 204) {
            throw new Exception("找不到该玩家 (204)");
        }
        if (responseCode != 200) {
            throw new Exception("API请求失败，错误代码: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    private UUID formatUUID(String uuidStr) {
        return UUID.fromString(
                uuidStr.substring(0, 8) + "-" +
                        uuidStr.substring(8, 12) + "-" +
                        uuidStr.substring(12, 16) + "-" +
                        uuidStr.substring(16, 20) + "-" +
                        uuidStr.substring(20)
        );
    }

    private String colorize(String color, String message) {
        switch (color.toLowerCase()) {
            case "black": return ChatColor.BLACK + message;
            case "dark_blue": return ChatColor.DARK_BLUE + message;
            case "dark_green": return ChatColor.DARK_GREEN + message;
            case "dark_aqua": return ChatColor.DARK_AQUA + message;
            case "dark_red": return ChatColor.DARK_RED + message;
            case "dark_purple": return ChatColor.DARK_PURPLE + message;
            case "gold": return ChatColor.GOLD + message;
            case "gray": return ChatColor.GRAY + message;
            case "dark_gray": return ChatColor.DARK_GRAY + message;
            case "blue": return ChatColor.BLUE + message;
            case "green": return ChatColor.GREEN + message;
            case "aqua": return ChatColor.AQUA + message;
            case "red": return ChatColor.RED + message;
            case "light_purple": return ChatColor.LIGHT_PURPLE + message;
            case "yellow": return ChatColor.YELLOW + message;
            case "white": return ChatColor.WHITE + message;
            default: return ChatColor.WHITE + message;
        }
    }
}