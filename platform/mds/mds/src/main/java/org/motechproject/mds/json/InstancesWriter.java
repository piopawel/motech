package org.motechproject.mds.json;

import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang.ArrayUtils;
import org.motechproject.mds.domain.ComboboxHolder;
import org.motechproject.mds.domain.Entity;
import org.motechproject.mds.domain.Field;
import org.motechproject.mds.domain.RelationshipHolder;
import org.motechproject.mds.service.MotechDataService;
import org.motechproject.mds.util.Constants;
import org.motechproject.mds.util.PropertyUtil;
import org.springframework.security.crypto.codec.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The <code>InstanceWriter</code> class is a wrapper for JsonWriter that provides methods to serialize entity
 * instances. Generated json contains array of instances objects definition where properties corresponds to entity
 * fields names and their values are appropriate formatted. It also contains one additional property: refId.
 * It is used to identify instance in the scope of generated file and it is required for relationships handling.
 *
 * @see org.motechproject.mds.domain.Entity
 * @see org.motechproject.mds.domain.Field
 * @see org.motechproject.mds.json.ObjectWriter
 * @see com.google.gson.stream.JsonWriter
 * @see org.motechproject.mds.json.InstancesReader
 */
public class InstancesWriter {

    private JsonWriter jsonWriter;
    private Entity entity;
    private MotechDataService dataService;
    private ObjectWriter objectWriter;

    public InstancesWriter(JsonWriter jsonWriter, Entity entity, ExportContext exportContext) {
        this.jsonWriter = jsonWriter;
        this.entity = entity;
        this.dataService = exportContext.getDataService(entity.getClassName());
        this.objectWriter = new ObjectWriter(jsonWriter);
    }

    public void writeInstances() throws IOException {
        jsonWriter.beginArray();
        for (Object instance : dataService.retrieveAll()) {
            writeInstance(instance);
        }
        jsonWriter.endArray();
    }

    public void writeInstance(Object instance) throws IOException {
        jsonWriter.beginObject();
        writeInstanceReferenceId(instance);
        for (Field field : entity.getFields()) {
            if (!field.isAutoGenerated()) {
                writeProperty(instance, field);
            }
        }
        jsonWriter.endObject();
    }

    private void writeInstanceReferenceId(Object instance) throws IOException {
        // lets just use database id as a refId
        Long refId = getInstanceRefId(instance);
        jsonWriter.name("refId").value(refId);
    }

    private void writeProperty(Object instance, Field field) throws IOException {
        if (field.getType().isRelationship()) {
            writeRelationshipProperty(field, instance);
        } else if (field.getType().isCombobox()) {
            writeComboboxProperty(field, instance);
        } else if (field.getType().isMap()) {
            writeMapProperty(field, instance);
        } else if (field.getType().isBlob()) {
            writeBlobProperty(field, instance);
        } else {
            writePlainProperty(field, instance);
        }
    }

    private void writeBlobProperty(Field field, Object instance) throws IOException {
        Object property = dataService.getDetachedField(instance, field.getName());
        if (property instanceof byte[]) {
            byte[] blob = (byte[]) property;
            String base64 = new String(Base64.encode(blob));
            objectWriter.writeFormatted(field.getName(), base64);
        } else if (property instanceof Byte[]) {
            byte[] blob = ArrayUtils.toPrimitive((Byte[]) property);
            String base64 = new String(Base64.encode(blob));
            objectWriter.writeFormatted(field.getName(), base64);
        } else {
            jsonWriter.name(field.getName()).nullValue();
        }
    }

    private void writePlainProperty(Field field, Object instance) throws IOException {
        Object property = PropertyUtil.safeGetProperty(instance, field.getName());
        objectWriter.writeFormatted(field.getName(), property);
    }

    private void writeMapProperty(Field field, Object instance) throws IOException {
        Object property = PropertyUtil.safeGetProperty(instance, field.getName());
        if (property instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) property;
            objectWriter.writerMap(field.getName(), map);
        } else {
            jsonWriter.name(field.getName()).nullValue();
        }
    }

    private void writeComboboxProperty(Field field, Object instance) throws IOException {
        Object property = PropertyUtil.safeGetProperty(instance, field.getName());
        if (null != property) {
            ComboboxHolder comboboxHolder = new ComboboxHolder(field);
            if (comboboxHolder.isAllowMultipleSelections()) {
                writeMultiValueComboboxProperty(field, property);
            } else {
                writeSingleValueComboboxProperty(field, property);
            }
        } else {
            jsonWriter.name(field.getName()).nullValue();
        }
    }

    private void writeSingleValueComboboxProperty(Field field, Object property) throws IOException {
        objectWriter.writeFormatted(field.getName(), property);
    }

    private void writeMultiValueComboboxProperty(Field field, Object property) throws IOException {
        if (property instanceof List) {
            List<?> combobox = (List<?>) property;
            objectWriter.writeArray(field.getName(), combobox);
        } else {
            jsonWriter.name(field.getName()).nullValue();
        }
    }

    private void writeRelationshipProperty(Field field, Object instance) throws IOException {
        Object property = PropertyUtil.safeGetProperty(instance, field.getName());
        if (null != property) {
            RelationshipHolder relationshipHolder = new RelationshipHolder(field);

            if (relationshipHolder.isManyToOne() || relationshipHolder.isOneToOne()) {
                jsonWriter.name(field.getName()).value(getRelatedReferenceId(property));
            } else if (relationshipHolder.isManyToMany() || relationshipHolder.isOneToMany()) {
                objectWriter.writeArray(field.getName(), getRelatedReferenceIds(property));
            } else {
                jsonWriter.name(field.getName()).nullValue();
            }
        } else {
            jsonWriter.name(field.getName()).nullValue();
        }
    }

    private List<Long> getRelatedReferenceIds(Object property) {
        if (property instanceof Collection) {
            Collection relatedInstancesCollection = (Collection) property;
            List<Long> relatedInstancesRefIds = new ArrayList<>(relatedInstancesCollection.size());
            for (Object relatedInstance : relatedInstancesCollection) {
                Long refId = getInstanceRefId(relatedInstance);
                relatedInstancesRefIds.add(refId);
            }
            return relatedInstancesRefIds;
        } else {
            return Collections.emptyList();
        }
    }

    private Long getRelatedReferenceId(Object property) {
        return getInstanceRefId(property);
    }

    private long getInstanceRefId(Object instance) {
        Long refId = (Long) PropertyUtil.safeGetProperty(instance, Constants.Util.ID_FIELD_NAME);
        return null != refId ? refId : -1L;
    }
}
