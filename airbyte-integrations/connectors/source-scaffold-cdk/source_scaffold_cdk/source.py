"""
MIT License

Copyright (c) 2020 Airbyte

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

from abc import ABC
from typing import Any, Iterable, List, Mapping, MutableMapping, Optional, Tuple, Union

import requests
from base_python import AbstractSource, HttpStream, Stream

"""
This file provides a stubbed example of how to use the Airbyte CDK to develop both a basic and an incremental stream for a HTTP API.

Here we see how one can go about structuring a Python source using the Airbyte CDK. The various TODOs are both implementation hints and steps - fulfilling
all the TODOs should be sufficient to implement one basic and one incremental stream from a source. This general pattern is how we ourselves use the
Airbyte CDK.

The approach here is not authoritative, and devs are free to use their own judgement.

There are additional required TODOs in the files within the sample_files folder and the spec.json file.

All comments in this class are instructive and should be deleted after the source is implemented.
"""


# Basic Stream
class ScaffoldCdkStream(HttpStream, ABC):
    # TODO: Fill in the url base. Required.
    url_base = "fill-in-this-base"

    def next_page_token(self, response: requests.Response) -> Optional[Mapping[str, Any]]:
        """
        TODO: Override this method to define a pagination strategy. Remove this method if not needed.
        """
        return None

    def request_params(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, any] = None, next_page_token: Mapping[str, Any] = None
    ) -> MutableMapping[str, Any]:
        """
        TODO: Override this method to define any query parameters to be set. Remove this method if not needed. Usually contains common params e.g. pagination size etc.
        """
        return {}


class MultipleShotPotatoGun(ScaffoldCdkStream):
    """
    TODO: Change class name to match the table/data source this strema corresponds to.
    """

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        """
        TODO: Override this method to define the path this stream corresponds to. E.g. if the url is https://potatogun.com/v1/multiple then this should
        return "multiple". Required.
        """
        return "fill_in_this_stream_specific_path"

    def parse_response(self, response: requests.Response, **kwargs) -> Iterable[Mapping]:
        """
        TODO: Override this method to define how a response is translated into one, or more, AirbyteRecordMessages.
        Returns an iterable of the response's field, by default. Remove this method if not needed.
        """
        yield {}


# Incremental Stream
class IncrementalScaffoldCdkStream(ScaffoldCdkStream, ABC):
    # TODO: Fill in to checkpoint stream reads after N records. This prevents re-reading of data if the stream fails for any reason.
    state_checkpoint_interval = None

    @property
    def cursor_field(self) -> Union[str, List[str]]:
        """
        Override to return the default cursor field used by this stream e.g: an API entity might always use created_at as the cursor field. This is
        usually id or date based. This field's presence tells the framework this in an incremental stream. Required for incremental.
        """
        return []

    def get_updated_state(self, current_stream_state: MutableMapping[str, Any], latest_record: Mapping[str, Any]) -> Mapping[str, Any]:
        """
        Override to determine the latest state after reading the latest record. This typically compared the cursor_field from the latest record and
        the current state and picks the 'most' recent cursor. This is how a stream's state is determined. Required for incremental.
        """
        return {}


class SingleShotPotatoGun(IncrementalScaffoldCdkStream):
    """
    TODO: Change class name to match the table/data source this stream corresponds to.
    """

    # TODO: Fill in the cursor_field. Required.
    cursor_field = "fill_in_this_stream_cursor_field"

    def path(self, **kwargs) -> str:
        """
        TODO: Override this method to define the path this stream corresponds to. E.g. if the url is https://potatogun.com/v1/single then this should
        return "single". Required.
        """
        return "customers"


# Source
class SourceScaffoldCdk(AbstractSource):
    def check_connection(self, logger, config) -> Tuple[bool, any]:
        """
        TODO: Implement a connection check for the API. The config parameter here exists to allow one to access user-given configuration. These are often
        api keys or secrets used to authenticate and authorize the Airbyte instance with the source. See https://github.com/airbytehq/airbyte/blob/master/airbyte-integrations/connectors/source-stripe/source_stripe/source.py#L232
        for an example. Required.
        """
        return True, None

    def streams(self, config: Mapping[str, Any]) -> List[Stream]:
        """
        TODO: Replace the MassPotatoGun stream when your own streams. The config parameter here exists to allow one to access user-given configuration
        when constructing streams. Required.
        """
        return [MultipleShotPotatoGun(), SingleShotPotatoGun()]