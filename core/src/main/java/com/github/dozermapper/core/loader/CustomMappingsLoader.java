/*
 * Copyright 2005-2019 Dozer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.dozermapper.core.loader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import com.github.dozermapper.core.CustomConverter;
import com.github.dozermapper.core.classmap.ClassMap;
import com.github.dozermapper.core.classmap.ClassMapBuilder;
import com.github.dozermapper.core.classmap.ClassMappings;
import com.github.dozermapper.core.classmap.Configuration;
import com.github.dozermapper.core.classmap.MappingFileData;
import com.github.dozermapper.core.config.BeanContainer;
import com.github.dozermapper.core.converters.CustomConverterContainer;
import com.github.dozermapper.core.converters.CustomConverterDescription;
import com.github.dozermapper.core.util.MappingUtils;

/**
 * Internal class that loads and parses custom xml mapping files into ClassMap objects. The ClassMap objects returned
 * from the load method are fully decorated and ready to be used by the internal mapping engine. Only intended for
 * internal use.
 */
public class CustomMappingsLoader {

    private final MappingsParser mappingsParser;
    private final ClassMapBuilder classMapBuilder;
    private final BeanContainer beanContainer;

    public CustomMappingsLoader(MappingsParser mappingsParser, ClassMapBuilder classMapBuilder, BeanContainer beanContainer) {
        this.mappingsParser = mappingsParser;
        this.classMapBuilder = classMapBuilder;
        this.beanContainer = beanContainer;
    }

    public LoadMappingsResult load(List<MappingFileData> mappings) {

        // 获取配置信息， 全局一份，多份会报错
        Configuration globalConfiguration = findConfiguration(mappings);

        ClassMappings customMappings = new ClassMappings(beanContainer);
        // Decorate the raw ClassMap objects and create ClassMap "prime" instances
        // 现有的mapping 信息，根据配置生成反转 mapping， 比如 order->dealer映射生成 dealer->order映射
        // 会生成逆向的classMap 所以返回的可能是多个ClassMap
        // 也会处理FieldMap中的部分字段， 比如copyByReference
        for (MappingFileData mappingFileData : mappings) {
            List<ClassMap> classMaps = mappingFileData.getClassMaps();
            ClassMappings customMappingsPrime = mappingsParser.processMappings(classMaps, globalConfiguration);
            customMappings.addAll(customMappingsPrime);
        }

        // Add default mappings using matching property names if wildcard policy
        // is true. The addDefaultFieldMappings will check the wildcard policy of each classmap
        // wildcard配置控制是否有默认映射， 是否只映射配置中的部分
        // 这里需要根据配置，将默认映射规则补上，一系列生成器为ClassMap补齐 FieldMap映射
        classMapBuilder.addDefaultFieldMappings(customMappings, globalConfiguration);

        Set<CustomConverterDescription> customConverterDescriptions = new LinkedHashSet<>();


        // 自定义转换器， 配置中读取， 类A和类B的通过类C进行转换
        // build up custom converter description objects
        if (globalConfiguration.getCustomConverters() != null && globalConfiguration.getCustomConverters().getConverters() != null) {
            for (CustomConverterDescription cc : globalConfiguration.getCustomConverters().getConverters()) {
                customConverterDescriptions.add(cc);
            }
        }

        // iterate through the classmaps and set all of the custom converters on them
        for (Entry<String, ClassMap> entry : customMappings.getAll().entrySet()) {
            ClassMap classMap = entry.getValue();
            if (classMap.getCustomConverters() != null) {
                classMap.getCustomConverters().setConverters(new ArrayList<>(customConverterDescriptions));
            } else {
                classMap.setCustomConverters(new CustomConverterContainer());
                classMap.getCustomConverters().setConverters(new ArrayList<>(customConverterDescriptions));
            }
        }

        // 增加了一个 UUID.class => UUID.class 的定制转换器， 看不懂为啥。。。
        addDefaultCustomConverters(globalConfiguration);

        return new LoadMappingsResult(customMappings, globalConfiguration);
    }

    private Configuration findConfiguration(List<MappingFileData> mappingFileDataList) {
        Configuration globalConfiguration = null;
        for (MappingFileData mappingFileData : mappingFileDataList) {
            if (mappingFileData.getConfiguration() != null) {
                //Only allow 1 global configuration
                if (globalConfiguration != null) {
                    MappingUtils
                            .throwMappingException("More than one global configuration found.  "
                                                   + "Only one global configuration block (<configuration></configuration>) can be specified across all mapping files.  "
                                                   + "You need to consolidate all global configuration blocks into a single one.");
                }
                globalConfiguration = mappingFileData.getConfiguration();
            }
        }

        //If global configuration was not specified, use defaults
        if (globalConfiguration == null) {
            globalConfiguration = new Configuration();
        }

        return globalConfiguration;
    }

    private void addDefaultCustomConverters(Configuration globalConfiguration) {
        if (globalConfiguration.getCustomConverters() != null
            && globalConfiguration.getCustomConverters().findConverter(UUID.class, UUID.class) == null) {
            CustomConverterDescription defaultUUIDConverter = new CustomConverterDescription();
            defaultUUIDConverter.setClassA(UUID.class);
            defaultUUIDConverter.setClassB(UUID.class);
            defaultUUIDConverter.setType(ByReferenceConverter.class);
            globalConfiguration.getCustomConverters().addConverter(defaultUUIDConverter);
        }
    }

    /**
     * Returns the source field value as a reference, regardless of other parameters.
     * Only intended for internal use.
     */
    public static class ByReferenceConverter implements CustomConverter {
        @Override
        public Object convert(Object existingDestinationFieldValue, Object sourceFieldValue, Class<?> destinationClass, Class<?> sourceClass) {
            return sourceFieldValue;
        }
    }

}
