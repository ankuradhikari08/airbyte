#
# MIT License
#
# Copyright (c) 2020 Airbyte
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

from typing import Any, Dict, Iterable, List, Mapping

import pendulum as pdm


def get_parent_stream_values(record: Dict, key_value_map: Dict) -> Dict:
    """
    Outputs the Dict with key:value slices for the stream.
    :: EXAMPLE:
        Input:
            records = [{dict}, {dict}, ...],
            key_value_map = {<slice_key_name>: <key inside record>}

        Output:
            {
                <slice_key_name> : records.<key inside record>.value,
            }
    """
    result = {}
    for key in key_value_map:
        value = record.get(key_value_map[key])
        if value:
            result[key] = value
    return result


def transform_change_audit_stamps(
    record: Dict, dict_key: str = "changeAuditStamps", props: List = ["created", "lastModified"], fields: List = ["time"]
) -> Mapping[str, Any]:

    """
    :: EXAMPLE `changeAuditStamps` input structure:
        {
            "changeAuditStamps": {
                "created": {"time": 1629581275000},
                "lastModified": {"time": 1629664544760}
            }
        }

    :: EXAMPLE output:
        {
            "created": "2021-08-21 21:27:55",
            "lastModified": "2021-08-22 20:35:44"
        }
    """

    target_dict: Dict = record.get(dict_key)
    for prop in props:
        # Update dict with flatten key:value
        for field in fields:
            record[prop] = pdm.from_timestamp(target_dict.get(prop).get(field) / 1000).to_datetime_string()
    record.pop(dict_key)

    return record


def date_str_from_date_range(record: Dict, prefix: str) -> str:
    """
    Makes the ISO8601 format date string from the input <prefix>.<part of the date>

    EXAMPLE:
        Input: record
        {
            "start.year": 2021, "start.month": 8, "start.day": 1,
            "end.year": 2021, "end.month": 9, "end.day": 31
        }

    EXAMPLE output:
        With `prefix` = "start"
            str:  "2021-08-13",

        With `prefix` = "end"
            str: "2021-09-31",
    """

    year = record.get(f"{prefix}.year")
    month = record.get(f"{prefix}.month")
    day = record.get(f"{prefix}.day")
    return pdm.date(year, month, day).to_date_string()


def transform_date_range(
    record: Dict,
    dict_key: str = "dateRange",
    props: List = ["start", "end"],
    fields: List = ["year", "month", "day"],
) -> Mapping[str, Any]:

    """
    :: EXAMPLE `dateRange` input structure in Analytics streams:
        {
            "dateRange": {
                "start": {"month": 8, "day": 13, "year": 2021},
                "end": {"month": 8, "day": 13, "year": 2021}
            }
        }
    :: EXAMPLE output:
        {
            "start_date": "2021-08-13",
            "end_date": "2021-08-13"
        }
    """
    # define list of tmp keys for cleanup.
    keys_to_remove = [dict_key, "start.day", "start.month", "start.year", "end.day", "end.month", "end.year", "start", "end"]

    target_dict: Dict = record.get(dict_key)
    for prop in props:
        # Update dict with flatten key:value
        for field in fields:
            record.update(**{f"{prop}.{field}": target_dict.get(prop).get(field)})
    # We build `start_date` & `end_date` fields from nested structure.
    record.update(**{"start_date": date_str_from_date_range(record, "start"), "end_date": date_str_from_date_range(record, "end")})
    # Cleanup tmp fields & nested used parts
    for key in keys_to_remove:
        if key in record:
            record.pop(key)
    return record


def transform_targeting_criteria(
    record: Dict,
    dict_key: str = "targetingCriteria",
) -> Mapping[str, Any]:

    """
    :: EXAMPLE `targetingCriteria` input structure:
        {
            "targetingCriteria": {
                "include": {
                    "and": [
                        {
                            "or": {
                                "urn:li:adTargetingFacet:titles": [
                                    "urn:li:title:100",
                                    "urn:li:title:10326",
                                    "urn:li:title:10457",
                                    "urn:li:title:10738",
                                    "urn:li:title:10966",
                                    "urn:li:title:11349",
                                    "urn:li:title:1159",
                                ]
                            }
                        },
                        {"or": {"urn:li:adTargetingFacet:locations": ["urn:li:geo:103644278"]}},
                        {"or": {"urn:li:adTargetingFacet:interfaceLocales": ["urn:li:locale:en_US"]}},
                    ]
                },
                "exclude": {
                    "or": {
                        "urn:li:adTargetingFacet:facet_Key1": [
                            "facet_test1",
                            "facet_test2",
                        ],
                        "urn:li:adTargetingFacet:facet_Key2": [
                            "facet_test3",
                            "facet_test4",
                        ],
                }
            }
        }

    :: EXAMPLE output:
        {
            "targetingCriteria": {
                "include": {
                    "and": [
                        {
                            "type": "urn:li:adTargetingFacet:titles",
                            "values": [
                                "urn:li:title:100",
                                "urn:li:title:10326",
                                "urn:li:title:10457",
                                "urn:li:title:10738",
                                "urn:li:title:10966",
                                "urn:li:title:11349",
                                "urn:li:title:1159",
                            ],
                        },
                        {
                            "type": "urn:li:adTargetingFacet:locations",
                            "values": ["urn:li:geo:103644278"],
                        },
                        {
                            "type": "urn:li:adTargetingFacet:interfaceLocales",
                            "values": ["urn:li:locale:en_US"],
                        },
                    ]
                },
                "exclude": {
                    "or": [
                        {
                            "type": "urn:li:adTargetingFacet:facet_Key1",
                            "values": ["facet_test1", "facet_test2"],
                        },
                        {
                            "type": "urn:li:adTargetingFacet:facet_Key2",
                            "values": ["facet_test3", "facet_test4"],
                        },
                    ]
                },
            }

    """
    targeting_criteria = record.get(dict_key)
    # transform `include`
    if "include" in targeting_criteria:
        and_list = targeting_criteria.get("include").get("and")
        for id, and_criteria in enumerate(and_list):
            or_dict = and_criteria.get("or")
            for key, value in or_dict.items():
                values = []
                if isinstance(value, list):
                    if isinstance(value[0], str):
                        values = value
                    elif isinstance(value[0], dict):
                        for v in value:
                            values.append(v)
                elif isinstance(key, dict):
                    values.append(key)
                # Replace the 'or' with {type:value}
                record["targetingCriteria"]["include"]["and"][id]["type"] = key
                record["targetingCriteria"]["include"]["and"][id]["values"] = values
                record["targetingCriteria"]["include"]["and"][id].pop("or")

    # transform `exclude` if present
    if "exclude" in targeting_criteria:
        or_dict = targeting_criteria.get("exclude").get("or")
        updated_exclude = {"or": []}
        for key, value in or_dict.items():
            values = []
            if isinstance(value, list):
                if isinstance(value[0], str):
                    values = value
                elif isinstance(value[0], dict):
                    for v in value:
                        value.append(v)
            elif isinstance(value, dict):
                value.append(value)
            updated_exclude["or"].append({"type": key, "values": values})
        record["targetingCriteria"]["exclude"] = updated_exclude

    return record


def transform_variables(
    record: Dict,
    dict_key: str = "variables",
) -> Mapping[str, Any]:

    """
    :: EXAMPLE `variables` input:
    {
        "variables": {
            "data": {
                "com.linkedin.ads.SponsoredUpdateCreativeVariables": {
                    "activity": "urn:li:activity:1234",
                    "directSponsoredContent": 0,
                    "share": "urn:li:share:1234",
                }
            }
        }
    }

    :: EXAMPLE output:
    {
        "variables": {
            "type": "com.linkedin.ads.SponsoredUpdateCreativeVariables",
            "values": [
                {"key": "activity", "value": "urn:li:activity:1234"},
                {"key": "directSponsoredContent", "value": 0},
                {"key": "share", "value": "urn:li:share:1234"},
            ],
        }
    }
    """

    variables = record.get(dict_key).get("data")
    for key, params in variables.items():
        record["variables"]["type"] = key
        record["variables"]["values"] = []
        for key, param in params.items():
            record["variables"]["values"].append({"key": key, "value": param})
        # Clean the nested structure
        record["variables"].pop("data")
    return record


def transform_data(records: List) -> Iterable[Mapping]:
    """
    We need to transform the nested complex data structures into simple key:value pair,
    to be properly normalised in the destination.
    """
    for record in records:

        if "changeAuditStamps" in record:
            record = transform_change_audit_stamps(record)

        if "dateRange" in record:
            record = transform_date_range(record)

        if "targetingCriteria" in record:
            record = transform_targeting_criteria(record)

        if "variables" in record:
            record = transform_variables(record)

        yield record
