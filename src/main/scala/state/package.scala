// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon

import viper.silver.ast
import viper.silicon.rules.PermMapDefinition

package object state {
  type PmCache =
    Map[
      (ast.Resource, Seq[QuantifiedBasicChunk]),
      PermMapDefinition]
}
