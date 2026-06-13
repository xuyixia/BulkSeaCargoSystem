package hyshweb.common;

public class ApiResponse {
    private final boolean success;
    private final String code;
    private final String message;
    private final Object data;

    private ApiResponse(boolean success, String code, String message, Object data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, "OK", "success", data);
    }

    public static ApiResponse fail(String code, String message) {
        return new ApiResponse(false, code, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }
}
