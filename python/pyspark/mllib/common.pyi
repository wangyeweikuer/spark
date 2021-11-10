#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from typing import Any, TypeVar

import pyspark.context

from py4j.java_gateway import JavaObject

C = TypeVar("C", bound=type)

def callJavaFunc(sc: pyspark.context.SparkContext, func: Any, *args: Any) -> Any: ...
def callMLlibFunc(name: str, *args: Any) -> Any: ...

class JavaModelWrapper:
    _java_model: JavaObject
    def __init__(self, java_model: JavaObject) -> None: ...
    def __del__(self) -> None: ...
    def call(self, name: str, *a: Any) -> Any: ...

def inherit_doc(cls: C) -> C: ...
