package com.github.rnewson.couchdb.rhino;

/**
 * Copyright 2009 Robert Newson
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.json.JSONObject;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericField;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.github.rnewson.couchdb.lucene.Tika;
import com.github.rnewson.couchdb.lucene.couchdb.Database;
import com.github.rnewson.couchdb.lucene.util.Constants;
import com.github.rnewson.couchdb.lucene.util.Conversion;
import com.github.rnewson.couchdb.lucene.util.Utils;

/**
 * Collect data from the user.
 * 
 * @author robertnewson
 * 
 */
public final class RhinoDocument extends ScriptableObject {

    private static class RhinoAttachment {
        private String attachmentName;
        private String fieldName;
    }

    private static class RhinoField {
        private NativeObject settings;
        private Object value;
    }

    private static final Map<String, Field.Index> Index = new HashMap<String, Field.Index>();

    private static final long serialVersionUID = 1L;

    private static final Map<String, Field.Store> Store = new HashMap<String, Field.Store>();

    static {
        Store.put("no", Field.Store.NO);
        Store.put("yes", Field.Store.YES);

        Index.put("analyzed", Field.Index.ANALYZED);
        Index.put("analyzed_no_norms", Field.Index.ANALYZED_NO_NORMS);
        Index.put("no", Field.Index.NO);
        Index.put("not_analyzed", Field.Index.NOT_ANALYZED);
        Index.put("not_analyzed_no_norms", Field.Index.NOT_ANALYZED_NO_NORMS);
    }

    public static Scriptable jsConstructor(final Context cx, final Object[] args, final Function ctorObj, final boolean inNewExpr) {
        final RhinoDocument doc = new RhinoDocument();
        if (args.length >= 2) {
            jsFunction_add(cx, doc, args, ctorObj);
        }
        return doc;
    }

    public static void jsFunction_add(final Context cx, final Scriptable thisObj, final Object[] args, final Function funObj) {
        final RhinoDocument doc = checkInstance(thisObj);

        if (args.length < 1 || args.length > 2) {
            throw Context.reportRuntimeError("Invalid number of arguments.");
        }

        if (args[0] == null) {
            throw Context.reportRuntimeError("first argument must be non-null.");
        }

        if (args[0] instanceof Undefined) {
            // Ignore
            return;
        }

        if (args.length == 2 && (args[1] == null || args[1] instanceof NativeObject == false)) {
            throw Context.reportRuntimeError("second argument must be an object.");
        }

        final RhinoField field = new RhinoField();
        field.value = args[0];
        if (args.length == 2) {
            field.settings = (NativeObject) args[1];
        }

        doc.fields.add(field);
    }

    public static void jsFunction_attachment(final Context cx, final Scriptable thisObj, final Object[] args, final Function funObj)
            throws IOException {
        final RhinoDocument doc = checkInstance(thisObj);
        if (args.length < 2) {
            throw Context.reportRuntimeError("Invalid number of arguments.");
        }

        final RhinoAttachment attachment = new RhinoAttachment();
        attachment.fieldName = args[0].toString();
        attachment.attachmentName = args[1].toString();
        doc.attachments.add(attachment);
    }

    private static RhinoDocument checkInstance(final Scriptable obj) {
        if (obj == null || !(obj instanceof RhinoDocument)) {
            throw Context.reportRuntimeError("called on incompatible object.");
        }
        return (RhinoDocument) obj;
    }

    /**
     * Foreign method for NativeObject.
     */
    private static String optString(final NativeObject obj, final String key, final String defaultValue) {
        if (obj.has(key, null)) {
            final Object value = obj.get(key, null);
            return value instanceof String ? (String) value : defaultValue;
        }
        return defaultValue;
    }

    private final List<RhinoAttachment> attachments = new ArrayList<RhinoAttachment>();

    private final List<RhinoField> fields = new ArrayList<RhinoField>();

    public RhinoDocument() {
    }

    public Document toDocument(final String id, final JSONObject defaults, final Database database) throws IOException {
        final Document result = new Document();

        // Add id.
        result.add(Utils.token("_id", id, true));

        // Add user-supplied fields.
        for (final RhinoField field : fields) {
            addField(field, defaults, result);
        }

        // Parse user-requested attachments.
        for (final RhinoAttachment attachment : attachments) {
            addAttachment(attachment, id, database, result);
        }

        return result;
    }

    @Override
    public String getClassName() {
        return "Document";
    }

    private void addAttachment(final RhinoAttachment attachment, final String id, final Database database, final Document out)
            throws IOException {
        final ResponseHandler<Void> handler = new ResponseHandler<Void>() {

            public Void handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
                final HttpEntity entity = response.getEntity();
                Tika.INSTANCE.parse(entity.getContent(), entity.getContentType().getValue(), attachment.fieldName, out);
                return null;
            }
        };

        database.handleAttachment(id, attachment.attachmentName, handler);
    }

    private void addField(final RhinoField field, final JSONObject defaults, final Document out) {
        String fieldName = defaults.optString("field", Constants.DEFAULT_FIELD);
        String store = defaults.optString("store", "no");
        String index = defaults.optString("index", "analyzed");
        String type = defaults.optString("type", "string");

        // Check for local settings.
        if (field.settings != null) {
            fieldName = optString(field.settings, "field", fieldName);
            store = optString(field.settings, "store", store);
            index = optString(field.settings, "index", index);
            type = optString(field.settings, "type", type);
        }

        final Field.Store storeObj = Store.get(store);
        if ("int".equals(type)) {
            out.add(new NumericField(fieldName, 4, storeObj, true).setIntValue(Conversion.convert(field.value, Integer.class)));
        } else if ("float".equals(type)) {
            out.add(new NumericField(fieldName, 4, storeObj, true).setFloatValue(Conversion.convert(field.value, Float.class)));
        } else if ("double".equals(type)) {
            out.add(new NumericField(fieldName, 8, storeObj, true).setDoubleValue(Conversion.convert(field.value, Double.class)));
        } else if ("long".equals(type)) {
            out.add(new NumericField(fieldName, 8, storeObj, true).setLongValue(Conversion.convert(field.value, Long.class)));
        } else if ("date".equals(type)) {
            final Date date = Conversion.convert(field.value, Date.class);
            out.add(new NumericField(fieldName, 8, storeObj, true).setLongValue(date.getTime()));
        } else if ("string".equals(type)) {
            out.add(new Field(fieldName, Conversion.convert(field.value).toString(), storeObj, Index.get(index)));
        }
    }

}
