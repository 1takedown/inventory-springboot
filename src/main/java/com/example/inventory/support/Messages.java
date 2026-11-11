package com.example.inventory.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class Messages {

    private final Map<String, Map<String, String>> dictionaries = new HashMap<>();

    public Messages() {
        dictionaries.put("en", Map.ofEntries(
                Map.entry("api.item.stockOverflow", "Restoring stock would exceed the maximum quantity"),
                Map.entry("api.login.required", "Username and password are required"),
                Map.entry("api.login.invalid", "Invalid credentials"),
                Map.entry("api.login.success", "Login successful"),
                Map.entry("api.auth.required", "Authentication token is required"),
                Map.entry("api.auth.invalid", "Invalid or expired authentication token"),
                Map.entry("api.auth.forbidden", "You do not have permission to perform this action"),
                Map.entry("api.auth.csrfInvalid", "The security token is invalid or expired"),
                Map.entry("api.auth.refreshInvalid", "The refresh token is invalid, expired, or has been reused"),
                Map.entry("api.login.rateLimited", "Too many login attempts; try again later"),
                Map.entry("api.login.removed", "This legacy login endpoint has been removed"),
                Map.entry("api.password.invalidCurrent", "The current password is incorrect"),
                Map.entry("api.password.policy", "Password must contain between 12 and 72 UTF-8 bytes"),
                Map.entry("api.user.invalidUsername", "Username must be 3-64 letters, numbers, dots, underscores, or hyphens"),
                Map.entry("api.user.invalidRole", "Role must be ADMIN, CASHIER or AUDITOR"),
                Map.entry("api.user.invalidId", "Invalid user id"),
                Map.entry("api.user.notFound", "User not found"),
                Map.entry("api.user.cannotChangeSelf", "Administrators cannot demote or deactivate their own account"),
                Map.entry("api.user.lastAdmin", "The last active administrator cannot be demoted or deactivated"),
                Map.entry("api.item.titleRequired", "Title is required"),
                Map.entry("api.item.quantityInvalid", "Quantity must be a whole number greater than or equal to 0"),
                Map.entry("api.item.costInvalid", "Cost must be non-negative with no more than two decimal places"),
                Map.entry("api.item.profitInvalid", "Profit must be non-negative with no more than two decimal places"),
                Map.entry("api.item.invalidId", "Invalid item id"),
                Map.entry("api.item.barcodeRequired", "Barcode is required"),
                Map.entry("api.item.notFound", "Item not found"),
                Map.entry("api.item.deleted", "Deleted item"),
                Map.entry("api.item.missingInOrder", "Item {itemId} was not found"),
                Map.entry("api.item.stockUnavailable", "Only {quantity} units available for {title}"),
                Map.entry("api.item.inUse", "Cannot delete item because it is referenced in one or more orders"),
                Map.entry("api.item.versionRequired", "The item version is required"),
                Map.entry("api.item.changed", "The item changed while you were editing it. Review the latest values"),
                Map.entry("api.order.invalidId", "Invalid order id"),
                Map.entry("api.order.notFound", "Order not found"),
                Map.entry("api.order.textRequired", "Order text is required"),
                Map.entry("api.order.pageInvalid", "The order page or page size is invalid"),
                Map.entry("api.order.itemRequired", "At least one order item is required"),
                Map.entry("api.order.lineInvalid", "Each order item needs a valid item_id and quantity"),
                Map.entry("api.order.lineNotFound", "The selected item does not belong to this order"),
                Map.entry("api.order.statusInvalid", "Order status must be DRAFT or CONFIRMED"),
                Map.entry("api.order.dateInvalid", "Order date must be a valid date in YYYY-MM-DD format"),
                Map.entry("api.order.pickupInvalid", "picked_up must be true or false"),
                Map.entry("api.order.notDraft", "Only draft orders can be edited, confirmed, or cancelled"),
                Map.entry("api.order.notConfirmed", "Only confirmed active orders support this operation"),
                Map.entry("api.order.lastItem", "A draft must keep at least one item; cancel it instead"),
                Map.entry("api.order.archiveNotFullyReturned", "Only fully returned orders can be archived"),
                Map.entry("api.order.alreadyArchived", "This order has already been archived"),
                Map.entry("api.return.invalid", "Choose either a full return or specific items"),
                Map.entry("api.return.reasonTooLong", "Return reason must not exceed 500 characters"),
                Map.entry("api.return.itemRequired", "Select at least one item to return"),
                Map.entry("api.return.lineInvalid", "Each return item needs a valid order_item_id and quantity"),
                Map.entry("api.return.lineNotFound", "A selected return item does not belong to this order"),
                Map.entry("api.return.quantityExceeded", "Return quantity exceeds the quantity remaining"),
                Map.entry("api.return.nothingRemaining", "This order has no items remaining to return"),
                Map.entry("api.uniqueConflict", "A record with that unique value already exists"),
                Map.entry("api.methodNotAllowed", "This operation is not supported"),
                Map.entry("api.request.invalid", "The request body or parameters are invalid"),
                Map.entry("api.request.unsupportedMediaType", "The request content type is not supported"),
                Map.entry("api.resource.notFound", "The requested API resource was not found"),
                Map.entry("api.money.outOfRange", "The monetary total is too large"),
                Map.entry("api.internalError", "Internal server error"),
                Map.entry("api.template.notFound", "Receipt template not found"),
                Map.entry("api.template.missing", "Receipt template file is missing on the server")
        ));
        dictionaries.put("zh-CN", Map.ofEntries(
                Map.entry("api.item.stockOverflow", "恢复库存将超过最大数量"),
                Map.entry("api.methodNotAllowed", "不支持此操作"),
                Map.entry("api.return.invalid", "请选择整单退货或指定退货商品"),
                Map.entry("api.return.reasonTooLong", "退货原因不能超过 500 个字符"),
                Map.entry("api.return.itemRequired", "请至少选择一件退货商品"),
                Map.entry("api.return.lineInvalid", "每件退货商品都需要有效的 order_item_id 和数量"),
                Map.entry("api.return.lineNotFound", "所选退货商品不属于此订单"),
                Map.entry("api.return.quantityExceeded", "退货数量超过剩余可退数量"),
                Map.entry("api.return.nothingRemaining", "此订单没有可退商品"),
                Map.entry("api.auth.csrfInvalid", "安全验证令牌无效或已过期"),
                Map.entry("api.login.required", "请输入用户名和密码"),
                Map.entry("api.login.invalid", "用户名或密码无效"),
                Map.entry("api.login.success", "登录成功"),
                Map.entry("api.auth.required", "需要身份验证令牌"),
                Map.entry("api.auth.invalid", "身份验证令牌无效或已过期"),
                Map.entry("api.auth.forbidden", "您没有执行此操作的权限"),
                Map.entry("api.auth.refreshInvalid", "刷新令牌无效、已过期或已被重复使用"),
                Map.entry("api.login.rateLimited", "登录尝试次数过多，请稍后重试"),
                Map.entry("api.login.removed", "旧版登录接口已被移除"),
                Map.entry("api.password.invalidCurrent", "当前密码不正确"),
                Map.entry("api.password.policy", "密码必须包含 12 至 72 个 UTF-8 字节"),
                Map.entry("api.user.invalidUsername", "用户名必须为 3-64 个字母、数字、点、下划线或连字符"),
                Map.entry("api.user.invalidRole", "角色必须是 ADMIN、CASHIER 或 AUDITOR"),
                Map.entry("api.user.invalidId", "用户 ID 无效"),
                Map.entry("api.user.notFound", "未找到用户"),
                Map.entry("api.user.cannotChangeSelf", "管理员不能降级或停用自己的账户"),
                Map.entry("api.user.lastAdmin", "不能降级或停用最后一个有效管理员"),
                Map.entry("api.item.titleRequired", "请输入商品标题"),
                Map.entry("api.item.quantityInvalid", "数量必须是大于或等于 0 的整数"),
                Map.entry("api.item.costInvalid", "成本必须为非负数，且最多包含两位小数"),
                Map.entry("api.item.profitInvalid", "利润必须为非负数，且最多包含两位小数"),
                Map.entry("api.item.invalidId", "商品 ID 无效"),
                Map.entry("api.item.barcodeRequired", "请提供条码"),
                Map.entry("api.item.notFound", "未找到商品"),
                Map.entry("api.item.deleted", "已删除的商品"),
                Map.entry("api.item.missingInOrder", "未找到商品 {itemId}"),
                Map.entry("api.item.stockUnavailable", "{title} 仅剩 {quantity} 件库存"),
                Map.entry("api.item.inUse", "无法删除商品，因为它被一个或多个订单引用"),
                Map.entry("api.item.versionRequired", "必须提供商品版本"),
                Map.entry("api.item.changed", "编辑期间商品已发生变化，请检查最新数据"),
                Map.entry("api.order.invalidId", "订单 ID 无效"),
                Map.entry("api.order.notFound", "未找到订单"),
                Map.entry("api.order.textRequired", "请输入订单文本"),
                Map.entry("api.order.pageInvalid", "订单页码或每页数量无效"),
                Map.entry("api.order.itemRequired", "订单至少需要一个商品"),
                Map.entry("api.order.lineInvalid", "每个订单商品都需要有效的 item_id 和数量"),
                Map.entry("api.order.lineNotFound", "所选商品不属于此订单"),
                Map.entry("api.order.statusInvalid", "订单状态必须是 DRAFT 或 CONFIRMED"),
                Map.entry("api.order.dateInvalid", "订单日期必须是 YYYY-MM-DD 格式的有效日期"),
                Map.entry("api.order.pickupInvalid", "picked_up 必须为 true 或 false"),
                Map.entry("api.order.notDraft", "只有草稿订单可以编辑、确认或取消"),
                Map.entry("api.order.notConfirmed", "只有已确认的有效订单支持此操作"),
                Map.entry("api.order.lastItem", "草稿至少需要保留一个商品；请改为取消订单"),
                Map.entry("api.order.archiveNotFullyReturned", "只有全部退货的订单才能归档"),
                Map.entry("api.order.alreadyArchived", "此订单已归档"),
                Map.entry("api.uniqueConflict", "已存在使用该唯一值的记录"),
                Map.entry("api.request.invalid", "请求内容或参数无效"),
                Map.entry("api.request.unsupportedMediaType", "不支持该请求内容类型"),
                Map.entry("api.resource.notFound", "未找到请求的 API 资源"),
                Map.entry("api.money.outOfRange", "金额总计超出可支持的范围"),
                Map.entry("api.internalError", "服务器内部错误"),
                Map.entry("api.template.notFound", "未找到小票模板"),
                Map.entry("api.template.missing", "服务器上缺少小票模板文件")
        ));
    }

    public String locale(HttpServletRequest request) {
        String explicit = request.getHeader("X-Locale");
        if (explicit == null || explicit.isBlank()) {
            explicit = request.getParameter("locale");
        }
        if (explicit == null || explicit.isBlank()) {
            explicit = request.getHeader("Accept-Language");
        }
        return explicit != null && explicit.trim().toLowerCase(Locale.ROOT).startsWith("zh") ? "zh-CN" : "en";
    }

    public String get(HttpServletRequest request, String key) {
        return get(locale(request), key, Map.of());
    }

    public String get(HttpServletRequest request, String key, Map<String, Object> parameters) {
        return get(locale(request), key, parameters);
    }

    public String get(String locale, String key, Map<String, Object> parameters) {
        String template = dictionaries.getOrDefault(locale, dictionaries.get("en"))
                .getOrDefault(key, dictionaries.get("en").getOrDefault(key, key));
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return template;
    }
}
