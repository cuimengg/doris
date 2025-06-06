// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <stdint.h>

#include <memory>

#include "common/status.h"
#include "operator.h"
#include "vec/core/block.h"

namespace doris {
#include "common/compile_check_begin.h"
class RuntimeState;

namespace pipeline {
class DataQueue;

class CacheSinkOperatorX;
class CacheSinkLocalState final : public PipelineXSinkLocalState<CacheSharedState> {
public:
    ENABLE_FACTORY_CREATOR(CacheSinkLocalState);
    CacheSinkLocalState(DataSinkOperatorXBase* parent, RuntimeState* state) : Base(parent, state) {}
    Status init(RuntimeState* state, LocalSinkStateInfo& info) override;
    Status open(RuntimeState* state) override;
    friend class CacheSinkOperatorX;
    using Base = PipelineXSinkLocalState<CacheSharedState>;
    using Parent = CacheSinkOperatorX;
};

class CacheSinkOperatorX final : public DataSinkOperatorX<CacheSinkLocalState> {
public:
    using Base = DataSinkOperatorX<CacheSinkLocalState>;

    friend class CacheSinkLocalState;
    CacheSinkOperatorX(int sink_id, int child_id, int dest_id);
#ifdef BE_TEST
    CacheSinkOperatorX() = default;
#endif
    ~CacheSinkOperatorX() override = default;
    Status init(const TDataSink& tsink) override {
        return Status::InternalError("{} should not init with TDataSink",
                                     DataSinkOperatorX<CacheSinkLocalState>::_name);
    }

    Status sink(RuntimeState* state, vectorized::Block* in_block, bool eos) override;

    std::shared_ptr<BasicSharedState> create_shared_state() const override {
        std::shared_ptr<BasicSharedState> ss = std::make_shared<CacheSharedState>();
        ss->id = operator_id();
        for (auto& dest : dests_id()) {
            ss->related_op_ids.insert(dest);
        }
        return ss;
    }
};

} // namespace pipeline
#include "common/compile_check_end.h"
} // namespace doris