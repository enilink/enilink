package net.enilink.platform.ldp.impl;

import net.enilink.komma.core.IValue;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import org.eclipse.rdf4j.model.Model;

import java.util.*;

public class OperationResponse {
    public static final int OK = 200;
    public static final int UNPROCESSED_ENTITY = 422;
    public static final int BAD_REQ = 400;
    public static final int CONFLICT = 409;
    public  static  final int UNSUPP_MEDIA = 415;

    public enum ValueType {
        IVALUE, IVALUES, MODEL, STATEMENTS;
    }

    private String msg;
    private int code ;
    private Map<ValueType, Object> values;

    public OperationResponse(){this.code = 200;}

    public OperationResponse(int code, String msg){
        this.code = code;
        this.msg = msg;
    }

    public OperationResponse(String err){ this(422, err);}

    public OperationResponse(Object obj){
        this();
        this.values = Collections.singletonMap(ValueType.IVALUE, obj);
    }

    public OperationResponse(List<IValue> vals){
        this();
        this.values = Collections.singletonMap(ValueType.IVALUES, vals);
    }

    public OperationResponse(Model model, URI predicate){
        this();
        this.values = new HashMap<>();
        this.values.put(ValueType.MODEL, model);
        this.values.put(ValueType.IVALUE, predicate);
    }

    public OperationResponse(Set<Statement> stmts){
        this();
        this.values = Collections.singletonMap(ValueType.STATEMENTS, stmts);
    }

    public String msg(){ return msg;}

    public int code() {return code;}

    public boolean hasError(){ return code >= 300 || code < 200;}

    public Object valueOf(ValueType value){return values.get(value);}
}
