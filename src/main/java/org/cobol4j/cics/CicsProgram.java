/*
 * cobol4j - COBOL Runtime Semantics as a Java DSL
 * Copyright (C) 2026 Gregg Wonderly
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package org.cobol4j.cics;

/**
 * A program that can be installed into a CICS region and dispatched
 * to handle transactions.
 * <p>
 * This is a functional interface — a CICS program is simply a function
 * that receives a {@link CicsContext} and does work.
 * <pre>{@code
 * CicsProgram customerInquiry = (ctx) -> {
 *     ctx.receive(commarea);
 *     // ... do work ...
 *     ctx.send(commarea);
 *     ctx.returnTransaction();
 * };
 *
 * region.install("CUSTINQ", customerInquiry)
 *       .transaction("CUST", "CUSTINQ");
 * }</pre>
 */
@FunctionalInterface
public interface CicsProgram {

    /**
     * Execute this program within a CICS task.
     *
     * @param ctx the execution context for this task
     */
    void execute(CicsContext ctx);
}
