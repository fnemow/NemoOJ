package com.nemo.OJbackendjudgeservice.judge.registry;

import com.google.gson.JsonElement;

/**
 * 类型注册表：提供类型名的反序列化和序列化能力。
 * 用于 ProblemSpec 校验时检查类型是否受支持。
 */
public interface TypeRegistry {

    /**
     * 从 JSON 反序列化为 Java 对象
     */
    Object deserialize(String typeName, JsonElement json);

    /**
     * 从 Java 对象序列化为 JSON
     */
    JsonElement serialize(String typeName, Object value);

    /**
     * 类型是否受支持
     */
    boolean isSupported(String typeName);
}
