"""Generate partial input definitions from SHA-pinned request/id/value evidence.

No network, runtime OpenAPI, or generated permissions. Generic keys not explicitly
reviewed remain needs_review; deprecations survive as exclusions.
"""
from __future__ import annotations
import argparse
import json
import math
import os
import re
from pathlib import Path
from tools.cloudflare_audit.schema import SchemaError, load_schema, resolve_json_pointer

PINNED_SHA256 = '06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461'
NATIVE_KEYS = frozenset('ssl always_use_https min_tls_version tls_1_3 http2 http3 development_mode browser_cache_ttl'.split())
DEDICATED_KEYS = ('aegis','automatic_platform_optimization','binary_ast','fonts','h2_prioritization','image_resizing','origin_h2_max_streams','origin_max_http_version','speed_brain','transformations_allowed_origins','transformations_c2pa','transformations_config')
ANNOTATIONS = {'description','title','example','default','x-auditable','x-stainless-skip','deprecated','x-stainless-deprecation-message','readOnly','writeOnly'}
VALIDATION = {'type','enum','properties','required','items','allOf','oneOf','anyOf','nullable','minimum','maximum','minLength','maxLength','pattern','format','minItems','maxItems','uniqueItems','additionalProperties'}

def default_schema_path():
    configured = os.environ.get('SCHEMA_FILE')
    return Path(configured) if configured is not None else Path(__file__).resolve().parents[2] / '.superpowers/cloudflare/openapi.json'

def deref(source,node,trail=()):
    if not isinstance(node,dict):raise SchemaError('setting schema must be an object')
    if '$ref' not in node:return node
    if set(node)!={'$ref'}:raise SchemaError('setting $ref siblings unsupported')
    ref=node['$ref']
    if ref in trail:raise SchemaError('cyclic setting reference')
    return deref(source,resolve_json_pointer(source,ref),trail+(ref,))

def properties(source,node):
    node=deref(source,node); result={}
    for part in node.get('allOf',[]):
        for key,values in properties(source,part).items():result.setdefault(key,[]).extend(values)
    for key,value in node.get('properties',{}).items():result.setdefault(key,[]).append(value)
    return result

def annotated(source,node,names,trail=()):
    if isinstance(node,list):return any(annotated(source,x,names,trail) for x in node)
    if not isinstance(node,dict):return False
    if '$ref' in node:
        ref=node['$ref']
        if ref in trail:raise SchemaError('cyclic setting annotation reference')
        return annotated(source,deref(source,node),names,trail+(ref,))
    return any(node.get(k) not in (None,False,'') for k in names) or any(annotated(source,v,names,trail) for v in node.values())

def expand(source,node,trail=()):
    if not isinstance(node,dict):raise SchemaError('setting schema must be an object')
    if '$ref' in node:
        ref=node['$ref']
        if ref in trail:raise SchemaError('cyclic setting input reference')
        return expand(source,deref(source,node),trail+(ref,))
    unknown=set(node)-VALIDATION-ANNOTATIONS
    if unknown:raise SchemaError(f'unsupported setting validation: {sorted(unknown)}')
    if 'type' in node and node['type'] not in ('object','array','string','boolean','number','integer'):raise SchemaError('unsupported setting type')
    if 'format' in node and node['format'] != 'hostname':raise SchemaError('unsupported setting format')
    for key in ('minimum','maximum'):
        if key in node and (type(node[key]) not in (int,float) or not math.isfinite(node[key])):raise SchemaError('invalid numeric setting constraint')
    for key in ('minLength','maxLength','minItems','maxItems'):
        if key in node and (type(node[key]) is not int or node[key]<0):raise SchemaError('invalid nonnegative setting constraint')
    for key in ('nullable','uniqueItems','readOnly','writeOnly','deprecated'):
        if key in node and type(node[key]) is not bool:raise SchemaError('invalid boolean setting annotation/constraint')
    if 'required' in node and (any(not isinstance(x,str) for x in node['required']) if isinstance(node['required'],list) else True):raise SchemaError('invalid required property names')
    if 'pattern' in node:
        try:re.compile(node['pattern'])
        except (TypeError,re.error) as error:raise SchemaError('invalid setting pattern') from error
    if 'items' in node and not isinstance(node['items'],dict):raise SchemaError('invalid setting items')
    if 'additionalProperties' in node and type(node['additionalProperties']) not in (bool,dict):raise SchemaError('invalid setting additionalProperties')
    for key in ('allOf','anyOf','oneOf'):
        if key in node and (not isinstance(node[key],list) or not node[key]):raise SchemaError('invalid setting composition')
    for key in ('required','enum'):
        if key in node and (not isinstance(node[key],list) or not node[key]):raise SchemaError('invalid setting list constraint')
    if 'properties' in node and not isinstance(node['properties'],dict):raise SchemaError('invalid setting properties')
    result={}
    for key,value in node.items():
        if key in ANNOTATIONS:continue
        if key=='properties':result[key]={k:expand(source,v,trail) for k,v in value.items()}
        elif key in ('allOf','anyOf','oneOf'):result[key]=[expand(source,x,trail) for x in value]
        elif key in ('items','additionalProperties') and isinstance(value,dict):result[key]=expand(source,value,trail)
        else:result[key]=value
    return result

def request(source,path,method='patch'):
    try:return deref(source,source['paths'][path][method]['requestBody'])['content']['application/json']['schema']
    except (KeyError,TypeError) as error:raise SchemaError('missing setting request') from error

def response_setting(source,path):
    try:
        response=deref(source,source['paths'][path]['get']['responses']['200'])['content']['application/json']['schema']
        return properties(source,response)['result'][-1]
    except (KeyError,TypeError) as error:raise SchemaError('missing setting response') from error

def status(source,input_schema,response_schema):
    if annotated(source,[input_schema,response_schema],{'deprecated','x-stainless-deprecation-message'}):return 'deprecated'
    if annotated(source,input_schema,{'readOnly'}):return 'needs_review'
    return 'approved'

def generate_zone_settings_definitions(source):
    result={'dedicated':{},'generic':{}}
    for key in DEDICATED_KEYS:
        path='/zones/{zone_id}/settings/'+key
        body=request(source,path); response=response_setting(source,path)
        ids={i for x in properties(source,response).get('id',[]) for i in deref(source,x).get('enum',[])}
        if len(ids)>1:raise SchemaError('ambiguous dedicated response id')
        state=status(source,body,response)
        value=deref(source,deref(source,body)['properties']['value'])
        merge=key=='transformations_config' and 'omitted fields are left unchanged' in value.get('description','')
        result['dedicated'][key]={'status':state,'schema':expand(source,body) if state=='approved' else None,'responseId':next(iter(ids),key),'partialObjectPatch':merge}
    generic_path='/zones/{zone_id}/settings/{setting_id}'
    body=deref(source,request(source,generic_path))
    wrapper={k:v for k,v in body.items() if k!='oneOf'}
    expand(source,wrapper)  # Never silently discard a new outer validation keyword.
    if set(wrapper)-ANNOTATIONS-{'type'}:raise SchemaError('generic wrapper constraints require review')
    # Prove the actual value request branch and its members before selecting any key.
    value_branches=[x for x in body.get('oneOf',[]) if set(deref(source,x).get('properties',{}))=={'value'}]
    if len(value_branches)!=1:raise SchemaError('ambiguous generic value request branch')
    value_union=deref(source,deref(source,value_branches[0])['properties']['value'])
    members={x.get('$ref') for x in value_union.get('oneOf',[])}
    if None in members or not members:raise SchemaError('invalid generic value union')
    branches=deref(source,response_setting(source,generic_path)).get('oneOf',[])
    for branch in branches:
        props=properties(source,branch)
        ids=[i for x in props.get('id',[]) for i in deref(source,x).get('enum',[])]
        values={x['$ref'] for x in props.get('value',[]) if '$ref' in x}
        if len(ids)!=1:continue
        key=ids[0]
        if key in result['generic']:raise SchemaError('duplicate generic setting id')
        state='needs_review'; reason='outside_reviewed_allowlist'; schema=None; binding={}
        if annotated(source,branch,{'deprecated','x-stainless-deprecation-message'}):state='deprecated';reason='deprecated_schema'
        elif key in NATIVE_KEYS:
            expected='#/components/schemas/zones_'+key+'_value'
            if values=={expected} and expected in members:
                # Retain actual branch constraints, replacing only the ambiguous value
                # union with its proven member. Product editing always requires a value.
                chosen=dict(deref(source,value_branches[0]))
                chosen['properties']={'value':{'$ref':expected}}
                chosen['required']=list(dict.fromkeys(chosen.get('required',[])+['value']))
                if 'type' in wrapper:
                    if chosen.get('type',wrapper['type'])!=wrapper['type']:raise SchemaError('generic type constraints conflict')
                    chosen['type']=wrapper['type']
                state=status(source,chosen,branch);reason='request_union_and_response_id_value'
                binding={'responseRef':branch.get('$ref'),'valueRef':expected}
                if state=='approved':schema=expand(source,chosen)
            else:reason='unproven_request_id_value_binding'
        result['generic'][key]={'status':state,'schema':schema,'reason':reason,'binding':binding,'partialObjectPatch':False,'responseId':key}
    for key in NATIVE_KEYS:
        result['generic'].setdefault(key,{'status':'needs_review','schema':None,'reason':'ambiguous_or_missing_id','binding':{},'partialObjectPatch':False,'responseId':key})
    result['universal']=expand(source,request(source,'/zones/{zone_id}/ssl/universal/settings'))
    result['purge']=expand(source,request(source,'/zones/{zone_id}/purge_cache','post'))
    return result

def render_kotlin(definitions):
    encoded=json.dumps(definitions,sort_keys=True,separators=(',',':'),ensure_ascii=True).replace('$',"${'$'}").replace('"""','${"\\"\\"\\""}')
    return '\n'.join([
        '// Generated by python3 -m tools.cloudflare_audit.generate_zone_settings. DO NOT EDIT.',
        '// Pinned OpenAPI SHA256: '+PINNED_SHA256,
        'package com.flarego.core.cloudflare.generated','', 'import kotlinx.serialization.json.*','',
        'object ZoneSettingSchemas {',
        '    private val definitions: JsonObject by lazy { Json.parseToJsonElement("""'+encoded+'""").jsonObject }',
        '    val genericAudit: JsonObject get() = definitions.getValue("generic").jsonObject',
        '    val universalInput: JsonObject get() = definitions.getValue("universal").jsonObject',
        '    val purgeInput: JsonObject get() = definitions.getValue("purge").jsonObject',
        '    fun definition(route: String, generic: Boolean): JsonObject? =',
        '        definitions.getValue(if (generic) "generic" else "dedicated").jsonObject[route] as? JsonObject',
        '    fun input(route: String, generic: Boolean): JsonObject? {',
        '        val entry = definition(route, generic) ?: return null',
        '        return if (entry["status"]?.jsonPrimitive?.content == "approved") entry["schema"] as? JsonObject else null',
        '    }','}',''])

def main():
    root=Path(__file__).resolve().parents[2]
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--schema',type=Path,default=default_schema_path())
    p.add_argument('--output',type=Path,default=root/'shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/ZoneSettingSchemas.kt')
    args=p.parse_args();output=render_kotlin(generate_zone_settings_definitions(load_schema(args.schema,PINNED_SHA256)))
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(output,encoding='utf-8')
if __name__=='__main__':main()
