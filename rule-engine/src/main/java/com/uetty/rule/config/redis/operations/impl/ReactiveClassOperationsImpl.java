package com.uetty.rule.config.redis.operations.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.uetty.rule.config.redis.annotation.RedisKey;
import com.uetty.rule.config.redis.annotation.RedisPrimaryKey;
import com.uetty.rule.config.redis.operations.ReactiveClassOperations;
import com.uetty.rule.utils.FunctionCollection;
import com.uetty.rule.utils.LambdaUtils;
import com.uetty.rule.utils.SerializableFunction;
import com.uetty.rule.utils.SerializedLambda;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.data.redis.connection.ReactiveHashCommands;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "varargs"})
public class ReactiveClassOperationsImpl<H, HK, HV> implements ReactiveClassOperations<H, HK, HV> {

    private final @NonNull ReactiveRedisTemplate<?, ?> template;
    private final @NonNull RedisSerializationContext<H, ?> serializationContext;

    private final @NonNull RedisSerializationContext<Object, ?> serializationString = RedisSerializationContext.java();

    private final static String CLASS = "@class";

    private static String DIVIDE = ":";

    /**
     * @return hashKey 序列化
     */
    private ByteBuffer rawHashKey(Object key) {
        return serializationContext.getHashKeySerializationPair().write(key);
    }

    private Map.Entry<HK, HV> deserializeHashEntry(Map.Entry<ByteBuffer, ByteBuffer> source) {
        return Collections.singletonMap(readHashKey(source.getKey()), readHashValue(source.getValue())).entrySet()
                .iterator().next();
    }

    private HK readHashKey(ByteBuffer value) {
        return (HK) serializationContext.getHashKeySerializationPair().read(value);
    }

    private ByteBuffer rawHashValue(Object key) {
        return serializationContext.getHashValueSerializationPair().write(key);
    }

    /**
     * @return key 序列化
     */
    private ByteBuffer rawKey(H key) {
        return serializationContext.getKeySerializationPair().write(key);
    }

    private HV readHashValue(ByteBuffer value) {
        return (HV) (value == null ? value : serializationContext.getHashValueSerializationPair().read(value));
    }

    private Object readObject(ByteBuffer value) {
        return (Object) (value == null ? value : serializationContext.getHashValueSerializationPair().read(value));
    }

    private String readString(ByteBuffer value) {
        return (String) (value == null ? value : serializationContext.getHashValueSerializationPair().read(value));
    }

    private List<HV> deserializeHashValues(List<ByteBuffer> source) {
        List<HV> values = new ArrayList<>(source.size());
        for (ByteBuffer byteBuffer : source) {
            values.add(readHashValue(byteBuffer));
        }
        return values;
    }

    private List<Object> deserializeObjects(List<ByteBuffer> source) {
        List<Object> values = new ArrayList<>(source.size());
        for (ByteBuffer byteBuffer : source) {
            values.add(readObject(byteBuffer));
        }
        return values;
    }

    /**
     * @param function 指令方法
     * @param <T>      类型
     * @return 创建Mono
     */
    private <T> Mono<T> createMono(Function<ReactiveHashCommands, Publisher<T>> function) {
        Assert.notNull(function, "Function must not be null!");
        return template.createMono(connection -> function.apply(connection.hashCommands()));
    }

    private <T> Flux<T> createFlux(Function<ReactiveHashCommands, Publisher<T>> function) {
        Assert.notNull(function, "Function must not be null!");
        return template.createFlux(connection -> function.apply(connection.hashCommands()));
    }

    @Override
    public Mono<Boolean> putClass(HV... values) {
        return putClass(Arrays.asList(values));
    }

    @Override
    public Mono<Boolean> putClass(Collection<HV> values) {
        return putClass(null, values);
    }

    @Override
    public Mono<Boolean> putClass(H key, HV... values) {
        return putClass(key, Arrays.asList(values));
    }

    @Override
    public Mono<Boolean> putClass(H key, Collection<HV> values) {
        Map<String, Object> map = Maps.newHashMap();
        Class<?> clazz = null;
        try {
            Field[] declaredFields = null;
            for (HV hv : values) {
                if (clazz == null) {
                    clazz = hv.getClass();
                    declaredFields = clazz.getDeclaredFields();
                }
                String hash = getHashKeyPre(hv);
                for (Field field : declaredFields) {
                    field.setAccessible(true);
                    map.put(hash + ":" + field.getName(), field.get(hv));
                }
                map.put(CLASS, clazz.getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Class<?> finalClazz = clazz;
        return createMono(connection -> Flux.fromIterable(() -> map.entrySet().iterator())
                .collectMap(entry -> rawHashKey(entry.getKey()), entry -> rawHashValue(entry.getValue()))
                .flatMap(serialized -> connection.hMSet(rawKey(getKey(key, finalClazz)), serialized)));
    }

    @Override
    public Mono<List<HV>> getClass(H key, FunctionCollection columns, Collection<HV> hashKey) {
        Assert.notNull(key, "key must not be null!");
        Assert.isTrue(hashKey != null & hashKey.size() > 0, "hashKey must not be null!");
        List<String> fields = columnsToString(columns.getFunctions());
        return this.getClassDetail(key, hashKey, (Class<HV>) hashKey.toArray()[0].getClass(), fields);
    }

    @Override
    public Mono<HV> getClass(H key, FunctionCollection columns, Object hashKey) {
        Assert.notNull(hashKey, "hashKey must not be null!");
        List<String> fields = columnsToString(columns.getFunctions());
        try {
            HV hv = (HV) hashKey;
            return this.getClassDetail(getKey(key, hv.getClass()), Lists.newArrayList(hashKey), (Class<HV>) hv.getClass(), fields).map(list -> list.stream().findFirst().get());
        } catch (ClassCastException e) {
            //强转错误
        }
        Assert.notNull(key, "key must not be null!");
        return this.getClassDetail(key, Lists.newArrayList(hashKey), null, fields).map(list -> list.stream().findFirst().get());
    }

    private H getKey(H key, Class<?> clazz) {
        if (key == null) {
            Assert.notNull(clazz, "clazz must not be null!");
            RedisKey redisKey = clazz.getAnnotation(RedisKey.class);
            Assert.notNull(clazz, "clazz must not be null!");
            key = (H) redisKey.value();
            Assert.notNull(key, "@RedisKey value 不能没有key");
        }
        return key;
    }

    private Mono<List<HV>> getClassDetail(H key, Collection hashKey, Class<HV> clazz, List<String> fields) {
        return this.getClassByName(key, clazz)
                .map(clazzNow -> {
                    boolean ret = clazz != null;
                    List<String> keys = Lists.newArrayList();
                    Map<Object, String> preKey = Maps.newHashMap();
                    ClassField<HV> classField = getClassField(clazzNow, fields, field -> keys.addAll(findHashKey(field, hashKey, preKey, ret)));
                    if (!ret) {
                        Assert.isTrue(classField.getPrimaryKey().size() == 1, "该方法只适用于单个主键");
                    }
                    classField.setKeys(keys);
                    return classField;
                })
                .flatMap(classField -> createMono(connection -> Flux.fromIterable(classField.getKeys())
                        .map(this::rawHashKey)
                        .collectList()
                        .flatMap(hks -> connection.hMGet(rawKey(key), hks)
                                .map(this::deserializeObjects))
                        .map(values -> toMap(classField.getKeys(), values))
                        .map(valueMap -> this.doFinally(classField, valueMap))));
    }

    /**
     * @param field   字段
     * @param hashKey 传入的值
     * @param preKey  前缀
     * @param ret     是否为对象
     * @return hashkey
     */
    private List<String> findHashKey(Field field, Collection hashKey, Map<Object, String> preKey, boolean ret) {
        List<String> list = Lists.newArrayList();
        for (Object o : hashKey) {
            if (ret) {
                try {
                    if (!preKey.containsKey(hashKey)) {
                        String hashKeyPre = getHashKeyPre((HV) o);
                        preKey.put(o, hashKeyPre);
                        list.add(new StringJoiner(DIVIDE).add(hashKeyPre).add(field.getName()).toString());
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            } else {
                list.add(new StringJoiner(DIVIDE).add(Objects.toString(hashKey)).add(field.getName()).toString());
            }
        }
        return list;
    }

    /**
     * @return map 转成属性对象
     */
    private List<HV> doFinally(ClassField<HV> classField, Map<String, Map<String, Object>> valueMap) {
        List<HV> hvs = Lists.newArrayList();
        try {
            for (Map<String, Object> entry : valueMap.values()) {
                HV hv = classField.getClazz().getDeclaredConstructor().newInstance();
                for (Field field : classField.getDeclaredFields()) {
                    field.setAccessible(true);
                    field.set(hv, entry.get(field.getName()));
                }
                hvs.add(hv);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hvs;
    }

    /**
     * @param keys   redis Hash Key
     * @param values redis Hash Value
     * @return 组成 FieldName-value Map
     */
    private Map<String, Map<String, Object>> toMap(List<String> keys, List<Object> values) {
        Assert.isTrue(keys.size() == values.size(), "key和value数量不相等 ");
        Map<String, Map<String, Object>> allMap = Maps.newHashMap();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Object value = values.get(i);
            String[] split = key.split(":");
            Assert.isTrue(split.length > 0, "分割属性出错 ");
            String hashKey = key.substring(0, key.lastIndexOf(":"));
            Map<String, Object> map = allMap.get(hashKey);
            if (map == null) {
                map = Maps.newHashMap();
            }
            String fieldName = split[split.length - 1];
            map.put(fieldName, value);
            allMap.put(hashKey, map);
        }
        return allMap;
    }

    /**
     * @param key   redis key
     * @param clazz 类型
     * @return 根据名称获取类型
     */
    private Mono<Class<HV>> getClassByName(H key, Class<HV> clazz) {
        return Mono.justOrEmpty(clazz)
                .switchIfEmpty(createMono(connection -> connection.hGet(rawKey(key), rawHashKey(CLASS))
                        .map(this::readString)
                        .flatMap(className -> {
                            try {
                                return Mono.justOrEmpty((Class<HV>) Class.forName(className));
                            } catch (ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                            return Mono.error(new RuntimeException("clazz 不存在"));
                        })));
    }

    /**
     * 获取 hashkey 前缀
     */
    private String getHashKeyPre(HV value) throws IllegalAccessException {
        Map<String, Object> keyMap = Maps.newHashMap();
        Class<?> clazz = value.getClass();
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field field : declaredFields) {
            if (field.getAnnotation(RedisPrimaryKey.class) != null) {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                Assert.notNull(fieldValue, "主键值不能为空");
                keyMap.put(field.getName(), fieldValue);
            }
        }
        Assert.notEmpty(keyMap, "Redis 对象不能没有 @RedisPrimaryKey 主键 ");
        return keyMap.entrySet()
                .stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .map(Object::toString)
                .collect(Collectors.joining(":"));

    }

    private <R> ClassField<HV> getClassField(Class<HV> clazz, List<String> fields, Function<Field, R> function) {
        List<String> primaryKey = Lists.newArrayList();
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field field : declaredFields) {
            if (field.getAnnotation(RedisPrimaryKey.class) != null) {
                primaryKey.add(field.getName());
            }
            if (fields.size() > 0 && !fields.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            function.apply(field);
        }
        return new ClassField<>(primaryKey, declaredFields, clazz);
    }

    private String methodToProperty(String name) {
        if (name.startsWith("is")) {
            name = name.substring(2);
        } else {
            if (!name.startsWith("get") && !name.startsWith("set")) {
                throw new RuntimeException("Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
            }
            name = name.substring(3);
        }
        if (name.length() == 1 || name.length() > 1 && !Character.isUpperCase(name.charAt(1))) {
            name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
        }

        return name;
    }

    private List<String> columnsToString(List<SerializableFunction<HV, ?>> columns) {
        if (columns == null) {
            return Lists.newArrayList();
        }
        return columns.stream().map(i -> getColumn(LambdaUtils.resolve(i))).collect(Collectors.toList());
    }

    /**
     * 获取 SerializedLambda 对应的列信息，从 lambda 表达式中推测实体类
     */
    private String getColumn(SerializedLambda lambda) {
        return methodToProperty(lambda.getImplMethodName());
    }
}

/**
 * class存储对象
 */
@Data
class ClassField<HV> {

    private List<String> primaryKey;//主键列表

    private Field[] declaredFields;//属性数组

    private List<String> keys;//REDIS HASH KEY

    private Class<HV> clazz;

    public ClassField(List<String> primaryKey, Field[] declaredFields, Class<HV> clazz) {
        this.primaryKey = primaryKey;
        this.declaredFields = declaredFields;
        this.clazz = clazz;
    }
}


