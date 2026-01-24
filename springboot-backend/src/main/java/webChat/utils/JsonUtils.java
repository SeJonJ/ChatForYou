package webChat.utils;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Gson gson = new GsonBuilder().create();

    public static String getStrOrNull(JsonObject obj, String key){
        if (!obj.isJsonNull() && obj.has(key)) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    public static String getStrOrEmpty(JsonObject obj, String key){
        if (!obj.isJsonNull() && obj.has(key)) {
            return obj.get(key).getAsString();
        }
        return "";
    }


    public static <T> T jsonToObj(String jsonString, Class<T> clazz) {
        try {
            return objectMapper.readValue(jsonString, clazz);
        } catch (Exception e) {
            try{
                return gson.fromJson(jsonString, clazz);
            }catch (Exception e2){
                throw new RuntimeException("Failed to convert JSON to Object", e);
            }
        }
    }

    public static String objToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert Object to JSON", e);
        }
    }

}
